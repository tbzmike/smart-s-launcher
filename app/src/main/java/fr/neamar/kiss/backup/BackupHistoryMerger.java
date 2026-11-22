package fr.neamar.kiss.backup;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Merges records collected on this installation into staged backup databases before restore.
 * The archive remains the primary copy; current-only events are added with their original keys
 * and timestamps. Repeated restores are idempotent.
 */
final class BackupHistoryMerger {
    private static final String LIVE_ALIAS = "smart_s_live_restore";
    private BackupHistoryMerger() { }

    static void merge(@NonNull File backupDatabase, @NonNull File liveDatabase,
                      @NonNull String databaseName) throws IOException {
        if (!liveDatabase.isFile()) return;
        if (!backupDatabase.isFile()) {
            copyDatabase(liveDatabase, backupDatabase);
            return;
        }
        SQLiteDatabase target = null;
        boolean attached = false;
        try {
            target = SQLiteDatabase.openDatabase(backupDatabase.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            target.execSQL("ATTACH DATABASE ? AS " + LIVE_ALIAS,
                    new Object[]{liveDatabase.getAbsolutePath()});
            attached = true;
            target.beginTransaction();
            try {
                if ("kiss.s3db".equals(databaseName)) {
                    mergeTable(target, "notification_history",
                            new String[]{"notification_id", "post_time"});
                    mergeTable(target, "history", new String[]{"record"});
                } else if ("smart_s_app_usage.db".equals(databaseName)) {
                    mergeTable(target, "timeline", new String[]{"event_key"});
                    mergeTable(target, "daily_usage", new String[]{"day_ms", "package_name"});
                    mergeTable(target, "daily_phone_state", new String[]{"day_ms"});
                    mergeTable(target, "package_state", new String[]{"package_name"});
                    mergeTable(target, "meta", new String[]{"meta_key"});
                    mergeMaximum(target, "timeline", "event_key",
                            new String[]{"end_ms", "duration_ms"});
                    mergeMaximum(target, "daily_usage", "package_name,day_ms",
                            new String[]{"duration_ms"});
                    mergeMaximum(target, "daily_phone_state", "day_ms",
                            new String[]{"screen_on_ms", "screen_off_ms", "unlock_count"});
                    mergePackageDates(target);
                    mergeNumericMeta(target);
                }
                target.setTransactionSuccessful();
            } finally {
                target.endTransaction();
            }
            target.execSQL("DETACH DATABASE " + LIVE_ALIAS);
            attached = false;
        } catch (RuntimeException failure) {
            throw new IOException("Unable to merge existing " + databaseName
                    + " into the restored backup", failure);
        } finally {
            if (target != null) {
                try {
                    if (attached) target.execSQL("DETACH DATABASE " + LIVE_ALIAS);
                } catch (RuntimeException ignored) { }
                try {
                    if (target.isOpen()) target.close();
                } catch (RuntimeException ignored) { }
            }
        }
    }

    private static void mergeTable(SQLiteDatabase db, String table, String[] keys) {
        if (!tableExists(db, "main", table) || !tableExists(db, LIVE_ALIAS, table)) return;
        List<String> columns = commonColumns(db, table);
        if (columns.isEmpty()) return;
        for (String key : keys) if (!columns.contains(key)) return;
        StringBuilder projection = new StringBuilder();
        StringBuilder identity = new StringBuilder();
        for (String column : columns) {
            if (projection.length() > 0) projection.append(',');
            projection.append("s.").append(q(column));
        }
        for (String key : keys) {
            if (identity.length() > 0) identity.append(" AND ");
            identity.append("b.").append(q(key)).append("=s.").append(q(key));
        }
        db.execSQL("INSERT INTO main." + q(table) + " (" + joinQuoted(columns) + ") "
                + "SELECT " + projection + " FROM " + LIVE_ALIAS + "." + q(table)
                + " s WHERE NOT EXISTS (SELECT 1 FROM main." + q(table)
                + " b WHERE " + identity + ")");
    }

    private static void mergeMaximum(SQLiteDatabase db, String table, String keyExpression,
                                     String[] values) {
        if (!tableExists(db, "main", table) || !tableExists(db, LIVE_ALIAS, table)) return;
        List<String> columns = commonColumns(db, table);
        for (String value : values) if (!columns.contains(value)) return;
        String[] keys = keyExpression.split(",");
        for (String key : keys) if (!columns.contains(key)) return;
        StringBuilder match = new StringBuilder();
        for (String key : keys) {
            if (match.length() > 0) match.append(" AND ");
            match.append("s.").append(q(key)).append("=main.").append(q(table))
                    .append('.').append(q(key));
        }
        StringBuilder assignments = new StringBuilder();
        for (String value : values) {
            if (assignments.length() > 0) assignments.append(',');
            assignments.append(q(value)).append("=MAX(").append(q(value))
                    .append(",COALESCE((SELECT s.").append(q(value)).append(" FROM ")
                    .append(LIVE_ALIAS).append('.').append(q(table)).append(" s WHERE ")
                    .append(match).append("),").append(q(value)).append("))");
        }
        db.execSQL("UPDATE main." + q(table) + " SET " + assignments
                + " WHERE EXISTS (SELECT 1 FROM " + LIVE_ALIAS + "." + q(table)
                + " s WHERE " + match + ")");
    }

    private static void mergePackageDates(SQLiteDatabase db) {
        if (!tableExists(db, "main", "package_state")
                || !tableExists(db, LIVE_ALIAS, "package_state")) return;
        List<String> columns = commonColumns(db, "package_state");
        if (!columns.containsAll(Arrays.asList("package_name", "first_install_ms", "last_update_ms"))) return;
        db.execSQL("UPDATE main.package_state SET "
                + "first_install_ms=MIN(first_install_ms,COALESCE((SELECT s.first_install_ms FROM "
                + LIVE_ALIAS + ".package_state s WHERE s.package_name=main.package_state.package_name),first_install_ms)),"
                + "last_update_ms=MAX(last_update_ms,COALESCE((SELECT s.last_update_ms FROM "
                + LIVE_ALIAS + ".package_state s WHERE s.package_name=main.package_state.package_name),last_update_ms)) "
                + "WHERE EXISTS (SELECT 1 FROM " + LIVE_ALIAS
                + ".package_state s WHERE s.package_name=main.package_state.package_name)");
    }

    private static void mergeNumericMeta(SQLiteDatabase db) {
        if (!tableExists(db, "main", "meta") || !tableExists(db, LIVE_ALIAS, "meta")) return;
        List<String> columns = commonColumns(db, "meta");
        if (!columns.containsAll(Arrays.asList("meta_key", "meta_value"))) return;
        db.execSQL("UPDATE main.meta SET meta_value=CAST(MAX(CAST(meta_value AS INTEGER),"
                + "COALESCE((SELECT CAST(s.meta_value AS INTEGER) FROM " + LIVE_ALIAS
                + ".meta s WHERE s.meta_key=main.meta.meta_key),CAST(meta_value AS INTEGER))) AS TEXT) "
                + "WHERE meta_key IN (SELECT meta_key FROM " + LIVE_ALIAS
                + ".meta WHERE meta_value GLOB '[0-9]*')");
    }

    private static boolean tableExists(SQLiteDatabase db, String schema, String table) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM " + schema
                + ".sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
            return c.moveToFirst();
        }
    }

    private static List<String> commonColumns(SQLiteDatabase db, String table) {
        Set<String> source = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA " + LIVE_ALIAS + ".table_info(" + q(table) + ")", null)) {
            while (c.moveToNext()) source.add(c.getString(1));
        }
        List<String> common = new ArrayList<>();
        try (Cursor c = db.rawQuery("PRAGMA main.table_info(" + q(table) + ")", null)) {
            while (c.moveToNext()) {
                String column = c.getString(1);
                if (!"_id".equals(column) && source.contains(column)) common.add(column);
            }
        }
        return common;
    }

    private static String joinQuoted(List<String> columns) {
        StringBuilder out = new StringBuilder();
        for (String column : columns) {
            if (out.length() > 0) out.append(',');
            out.append(q(column));
        }
        return out.toString();
    }

    private static String q(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static void copyDatabase(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create " + parent);
        }
        try (java.io.InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(source));
             java.io.OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(destination))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        destination.setLastModified(source.lastModified());
    }
}
