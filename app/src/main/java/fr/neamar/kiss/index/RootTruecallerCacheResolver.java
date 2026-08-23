package fr.neamar.kiss.index;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Root-assisted, read-only fallback for caller identities cached privately by Truecaller.
 *
 * <p>The implementation deliberately does not hard-code a Truecaller database, table or column
 * name. Root is used only to discover/copy candidate SQLite files into Smart S's own cache. The
 * copied databases are then inspected locally and read-only. Truecaller's original storage is never
 * modified.</p>
 */
final class RootTruecallerCacheResolver {
    private static final int MAX_DATABASE_FILES = 60;
    private static final int MAX_TABLES_PER_DB = 120;
    private static final int MAX_ROWS_PER_TABLE = 5000;
    private static final String CACHE_PREFS = "truecaller-root-name-cache";
    private static final String NAME_PREFIX = "name:";
    private static final String NAME_TIME_PREFIX = "name-time:";
    private static final String MISS_PREFIX = "miss:";
    private static final long MISS_RETRY_MS = 30 * 60_000L;
    private static final long NAME_REFRESH_MS = 7L * 24L * 60L * 60_000L;

    private RootTruecallerCacheResolver() { }

    static Map<String, String> loadNames(Context context, List<String> numbers) {
        Map<String, String> result = new HashMap<>();
        if (context == null || numbers == null || numbers.isEmpty()) return result;

        TargetIndex allTargets = new TargetIndex(numbers);
        if (allTargets.normalized.isEmpty()) return result;

        SharedPreferences cache = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        List<String> scanNumbers = new ArrayList<>();
        for (String normalized : allTargets.normalized) {
            String cachedName = cache.getString(NAME_PREFIX + normalized, "");
            long cachedAt = cache.getLong(NAME_TIME_PREFIX + normalized, 0L);
            if (CallerIdentityResolver.isUsableName(cachedName)) {
                result.put(normalized, cachedName.trim());
                if (now - cachedAt < NAME_REFRESH_MS) continue;
            }
            long lastMiss = cache.getLong(MISS_PREFIX + normalized, 0L);
            if (now - lastMiss < MISS_RETRY_MS) continue;
            String original = allTargets.originalByNormalized.get(normalized);
            if (!TextUtils.isEmpty(original)) scanNumbers.add(original);
        }
        if (scanNumbers.isEmpty()) return result;

        TargetIndex scanTargets = new TargetIndex(scanNumbers);
        List<String> databases = discoverDatabases();
        // Do not record a miss when root is unavailable or Truecaller storage cannot even be
        // discovered. This lets a later Magisk/su grant take effect immediately.
        if (databases.isEmpty()) return result;

        File probeDir = new File(context.getCacheDir(), "truecaller_root_probe");
        if (!probeDir.exists() && !probeDir.mkdirs()) return result;

        Map<String, String> scanned = new HashMap<>();
        Set<String> conflicts = new HashSet<>();
        int index = 0;
        try {
            for (String source : databases) {
                if (scanned.size() + conflicts.size() >= scanTargets.normalized.size()) break;
                if (index >= MAX_DATABASE_FILES) break;

                File localDb = new File(probeDir, "tc_" + index++ + ".db");
                if (!copyDatabaseWithRoot(source, localDb)) {
                    cleanupLocal(localDb);
                    continue;
                }

                inspectDatabase(localDb, scanTargets, scanned, conflicts);
                cleanupLocal(localDb);
            }
        } finally {
            deleteRecursively(probeDir);
        }

        for (String conflict : conflicts) scanned.remove(conflict);

        SharedPreferences.Editor editor = cache.edit();
        for (String normalized : scanTargets.normalized) {
            String name = scanned.get(normalized);
            if (CallerIdentityResolver.isUsableName(name)) {
                result.put(normalized, name.trim());
                editor.putString(NAME_PREFIX + normalized, name.trim());
                editor.putLong(NAME_TIME_PREFIX + normalized, now);
                editor.remove(MISS_PREFIX + normalized);
            } else {
                editor.putLong(MISS_PREFIX + normalized, now);
            }
        }
        editor.apply();
        return result;
    }

    static String getName(String number, Map<String, String> names) {
        if (TextUtils.isEmpty(number) || names == null || names.isEmpty()) return "";
        String normalized = normalize(number);
        if (normalized.isEmpty()) return "";
        String value = names.get(normalized);
        return value == null ? "" : value;
    }

    private static List<String> discoverDatabases() {
        String command =
                "for d in /data/user/0/com.truecaller/databases /data/data/com.truecaller/databases; do "
                        + "[ -d \"$d\" ] && find \"$d\" -maxdepth 1 -type f "
                        + "! -name '*-wal' ! -name '*-shm' ! -name '*-journal'; done "
                        + "2>/dev/null | head -n " + MAX_DATABASE_FILES;
        List<String> output = runRootLines(command);
        List<String> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : output) {
            String path = line == null ? "" : line.trim();
            if (!path.isEmpty() && seen.add(path)) unique.add(path);
        }
        return unique;
    }

    private static boolean copyDatabaseWithRoot(String source, File destination) {
        if (TextUtils.isEmpty(source) || destination == null) return false;
        int uid = android.os.Process.myUid();
        String src = shellQuote(source);
        String dst = shellQuote(destination.getAbsolutePath());
        String walDst = shellQuote(destination.getAbsolutePath() + "-wal");
        String shmDst = shellQuote(destination.getAbsolutePath() + "-shm");
        String command = "cp -f " + src + " " + dst
                + " && chown " + uid + ":" + uid + " " + dst
                + " && chmod 600 " + dst
                + "; if [ -f " + src + "-wal ]; then cp -f " + src + "-wal " + walDst
                + " && chown " + uid + ":" + uid + " " + walDst + " && chmod 600 " + walDst + "; fi"
                + "; if [ -f " + src + "-shm ]; then cp -f " + src + "-shm " + shmDst
                + " && chown " + uid + ":" + uid + " " + shmDst + " && chmod 600 " + shmDst + "; fi";
        return runRootStatus(command) == 0 && destination.isFile() && destination.canRead();
    }

    private static void inspectDatabase(File file, TargetIndex targets,
                                        Map<String, String> result, Set<String> conflicts) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            List<String> tables = readTables(db);
            int tableCount = 0;
            for (String table : tables) {
                if (tableCount++ >= MAX_TABLES_PER_DB) break;
                if (result.size() + conflicts.size() >= targets.normalized.size()) break;
                inspectTable(db, table, targets, result, conflicts);
            }
        } catch (RuntimeException ignored) {
            // Candidate files are discovered dynamically; non-SQLite or incompatible databases are
            // expected and simply skipped.
        } finally {
            if (db != null) {
                try { db.close(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static List<String> readTables(SQLiteDatabase db) {
        List<String> tables = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
                null)) {
            while (c.moveToNext()) {
                String table = c.getString(0);
                if (!TextUtils.isEmpty(table)) tables.add(table);
            }
        } catch (RuntimeException ignored) { }
        return tables;
    }

    private static void inspectTable(SQLiteDatabase db, String table, TargetIndex targets,
                                     Map<String, String> result, Set<String> conflicts) {
        List<Column> numberColumns = new ArrayList<>();
        List<Column> nameColumns = new ArrayList<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + quoteIdentifier(table) + ")", null)) {
            int nameIndex = c.getColumnIndex("name");
            while (c.moveToNext()) {
                String column = nameIndex >= 0 ? c.getString(nameIndex) : c.getString(1);
                if (TextUtils.isEmpty(column)) continue;
                String lower = column.toLowerCase(Locale.ROOT);
                if (isNumberColumn(lower)) numberColumns.add(new Column(column));
                if (isNameColumn(lower)) nameColumns.add(new Column(column));
            }
        } catch (RuntimeException ignored) {
            return;
        }
        if (numberColumns.isEmpty() || nameColumns.isEmpty()) return;

        List<Column> selected = new ArrayList<>();
        selected.addAll(numberColumns);
        for (Column column : nameColumns) {
            boolean duplicate = false;
            for (Column existing : selected) {
                if (existing.name.equals(column.name)) { duplicate = true; break; }
            }
            if (!duplicate) selected.add(column);
        }

        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < selected.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append(quoteIdentifier(selected.get(i).name));
        }
        sql.append(" FROM ").append(quoteIdentifier(table))
                .append(" LIMIT ").append(MAX_ROWS_PER_TABLE);

        try (Cursor c = db.rawQuery(sql.toString(), null)) {
            while (c.moveToNext()) {
                String target = findTarget(c, numberColumns, selected, targets);
                if (target.isEmpty() || conflicts.contains(target)) continue;
                String name = bestName(c, nameColumns, selected);
                if (!CallerIdentityResolver.isUsableName(name)) continue;

                String previous = result.get(target);
                if (previous == null || previous.equalsIgnoreCase(name)) {
                    result.put(target, name.trim());
                } else {
                    conflicts.add(target);
                    result.remove(target);
                }
            }
        } catch (RuntimeException ignored) { }
    }

    private static String findTarget(Cursor cursor, List<Column> numberColumns,
                                     List<Column> selected, TargetIndex targets) {
        for (Column numberColumn : numberColumns) {
            int index = selectedIndex(selected, numberColumn.name);
            if (index < 0) continue;
            String value = safeGet(cursor, index);
            String normalized = normalize(value);
            if (normalized.isEmpty()) continue;
            if (targets.normalized.contains(normalized)) return normalized;
            String suffix = suffix(normalized);
            String matched = targets.uniqueSuffix.get(suffix);
            if (matched != null) return matched;
        }
        return "";
    }

    private static String bestName(Cursor cursor, List<Column> nameColumns, List<Column> selected) {
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        String first = "";
        String last = "";
        for (Column nameColumn : nameColumns) {
            int index = selectedIndex(selected, nameColumn.name);
            if (index < 0) continue;
            String value = safeGet(cursor, index).trim();
            if (!CallerIdentityResolver.isUsableName(value)) continue;
            String lower = nameColumn.name.toLowerCase(Locale.ROOT);
            if (lower.contains("first") && lower.contains("name")) first = value;
            if (lower.contains("last") && lower.contains("name")) last = value;
            int score = nameColumnScore(lower);
            if (score > bestScore || (score == bestScore && value.length() > best.length())) {
                best = value;
                bestScore = score;
            }
        }
        if (!first.isEmpty() && !last.isEmpty()) {
            String combined = first + " " + last;
            if (CallerIdentityResolver.isUsableName(combined) && bestScore < 100) return combined;
        }
        return best;
    }

    private static int selectedIndex(List<Column> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name.equals(name)) return i;
        }
        return -1;
    }

    private static boolean isNumberColumn(String column) {
        return column.contains("phone") || column.contains("number")
                || column.contains("normalized") || column.contains("msisdn")
                || column.contains("e164") || column.equals("address")
                || column.endsWith("_address");
    }

    private static boolean isNameColumn(String column) {
        if (column.contains("package") || column.contains("class") || column.contains("file")
                || column.contains("database") || column.contains("table")) return false;
        return column.contains("name") || column.contains("display")
                || column.contains("label") || column.contains("title");
    }

    private static int nameColumnScore(String column) {
        if (column.equals("display_name") || column.equals("displayname")) return 140;
        if (column.equals("full_name") || column.equals("fullname")) return 135;
        if (column.equals("caller_name") || column.equals("callername")) return 130;
        if (column.equals("name")) return 125;
        if (column.contains("display") && column.contains("name")) return 115;
        if (column.contains("profile") && column.contains("name")) return 110;
        if (column.contains("name")) return 85;
        if (column.contains("label") || column.contains("title")) return 35;
        return 0;
    }

    private static String safeGet(Cursor c, int index) {
        try {
            return c.isNull(index) ? "" : c.getString(index);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static List<String> runRootLines(String command) {
        List<String> lines = new ArrayList<>();
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            }
            process.waitFor();
        } catch (Exception ignored) {
            lines.clear();
        } finally {
            if (process != null) process.destroy();
        }
        return lines;
    }

    private static int runRootStatus(String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            return process.waitFor();
        } catch (Exception ignored) {
            return -1;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String normalize(String number) {
        if (TextUtils.isEmpty(number)) return "";
        String normalized = PhoneNumberUtils.normalizeNumber(number);
        return normalized == null ? "" : normalized;
    }

    private static String suffix(String normalized) {
        if (TextUtils.isEmpty(normalized)) return "";
        String digits = normalized.replaceAll("[^0-9]", "");
        return digits.length() >= 7 ? digits.substring(digits.length() - 7) : "";
    }

    private static void cleanupLocal(File database) {
        if (database == null) return;
        deleteQuietly(database);
        deleteQuietly(new File(database.getAbsolutePath() + "-wal"));
        deleteQuietly(new File(database.getAbsolutePath() + "-shm"));
        deleteQuietly(new File(database.getAbsolutePath() + "-journal"));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        deleteQuietly(file);
    }

    private static void deleteQuietly(File file) {
        if (file != null) {
            try { file.delete(); } catch (SecurityException ignored) { }
        }
    }

    private static final class Column {
        final String name;
        Column(String name) { this.name = name; }
    }

    private static final class TargetIndex {
        final Set<String> normalized = new HashSet<>();
        final Map<String, String> uniqueSuffix = new HashMap<>();
        final Map<String, String> originalByNormalized = new HashMap<>();

        TargetIndex(List<String> numbers) {
            Set<String> duplicateSuffixes = new HashSet<>();
            for (String number : numbers) {
                String normalizedNumber = normalize(number);
                if (normalizedNumber.isEmpty()) continue;
                normalized.add(normalizedNumber);
                originalByNormalized.put(normalizedNumber, number);
                String suffix = suffix(normalizedNumber);
                if (suffix.isEmpty()) continue;
                String previous = uniqueSuffix.get(suffix);
                if (previous != null && !previous.equals(normalizedNumber)) {
                    duplicateSuffixes.add(suffix);
                } else {
                    uniqueSuffix.put(suffix, normalizedNumber);
                }
            }
            for (String duplicate : duplicateSuffixes) uniqueSuffix.remove(duplicate);
        }
    }
}
