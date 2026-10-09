package fr.neamar.kiss.db;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.CancellationSignal;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fr.neamar.kiss.utils.Log;

public class DBHelper {
    private static final String TAG = DBHelper.class.getSimpleName();
    private DBHelper() {
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }

    private static SQLiteDatabase getDatabase(Context context) {
        return DatabaseRecovery.getDatabase(context);
    }

    private static List<ValuedHistoryRecord> readCursor(Cursor cursor) {
        cursor.moveToFirst();

        List<ValuedHistoryRecord> records = new ArrayList<>(cursor.getCount());
        while (!cursor.isAfterLast()) {
            ValuedHistoryRecord entry = new ValuedHistoryRecord();

            entry.record = cursor.getString(0);
            entry.value = cursor.getInt(1);
            if (cursor.getColumnCount() > 2) entry.timestamp = cursor.getLong(2);
            if (cursor.getColumnCount() > 3) entry.sequence = cursor.getLong(3);

            records.add(entry);
            cursor.moveToNext();
        }
        cursor.close();

        return records;
    }

    /**
     * Insert new item into history
     *
     * @param context android context
     * @param query   query to insert
     * @param record  record to insert
     */
    public static void insertHistory(Context context, String query, String record) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;
        ContentValues values = new ContentValues();
        values.put("query", query);
        values.put("record", record);
        values.put("timeStamp", System.currentTimeMillis());
        db.insert("history", null, values);

        if (Math.random() <= 0.005) {
            // Roughly every 200 inserts, clean up the history of items older than 3 months
            long twoMonthsAgo = 7776000000L; // 1000 * 60 * 60 * 24 * 30 * 3;
            db.delete("history", "timeStamp < ?", new String[]{Long.toString(System.currentTimeMillis() - twoMonthsAgo)});
            // And vacuum the DB for speed
            db.execSQL("VACUUM");
        }

        });
    }

    public static void removeFromHistory(Context context, String record) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;
        db.delete("history", "record = ?", new String[]{record});

        });
    }

    public static void clearHistory(Context context) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;
        db.delete("history", "", null);

        });
    }

    private static Cursor getHistoryByFrecency(SQLiteDatabase db, int limit) {
        // Since smart history sql uses a group by we don't use the whole history but a limit of recent apps
        int historyWindowSize = limit * 30;

        // order history based on frequency * recency
        // frequency = #launches_for_app / #all_launches
        // recency = 1 / position_of_app_in_normal_history
        String sql = "SELECT record, count(*) FROM " +
                " (" +
                "   SELECT * FROM history ORDER BY _id DESC " +
                "   LIMIT " + historyWindowSize +
                " ) small_history " +
                " GROUP BY record " +
                " ORDER BY " +
                "   count(*) * 1.0 / (select count(*) from history LIMIT " + historyWindowSize + ") / ((SELECT _id FROM history ORDER BY _id DESC LIMIT 1) - max(_id) + 0.001) " +
                " DESC " +
                " LIMIT " + limit;
        return db.rawQuery(sql, null);
    }

    private static Cursor getHistoryByFrequency(SQLiteDatabase db, int limit) {
        // order history based on frequency
        String sql = "SELECT record, count(*) FROM history" +
                " GROUP BY record " +
                " ORDER BY count(*) DESC " +
                " LIMIT " + limit;
        return db.rawQuery(sql, null);
    }

    private static Cursor getHistoryByRecency(SQLiteDatabase db, int limit) {
        // "SELECT DISTINCT record ... ORDER BY _id DESC" does not mean "latest occurrence of
        // each record first". SQLite is free to choose which duplicate row survives DISTINCT,
        // so a just-launched app can briefly appear from the warm UI and then disappear again
        // when the authoritative History query completes. Group by the identity and explicitly
        // order by its newest row id instead.
        return db.rawQuery(
                "SELECT record, 1, MAX(timeStamp), MAX(_id) FROM history "
                        + "GROUP BY record ORDER BY MAX(_id) DESC LIMIT ?",
                new String[]{Integer.toString(limit)});
    }

    /**
     * Get the most used history items adaptively based on a set period of time
     *
     * @param db    The SQL db
     * @param hours How many hours back we want to test frequency against
     * @param limit Maximum result size
     * @return Cursor
     */
    private static Cursor getHistoryByAdaptive(SQLiteDatabase db, int hours, int limit) {
        // order history based on frequency
        String sql = "SELECT record, count(*) FROM history " +
                "WHERE timeStamp >= 0 " +
                "AND timeStamp >" + (System.currentTimeMillis() - (hours * 3600000L)) +
                " GROUP BY record " +
                " ORDER BY count(*) DESC " +
                " LIMIT " + limit;
        return db.rawQuery(sql, null);
    }

    /**
     * Get the history items used closest to this time of day, for each day old an item is it has
     * one less hour of time weight. So we limit the number of days of history to 24 days in the
     * WHERE clause, this should also help with speed on large histories.
     * <p>
     * This is done by taking the max of a triangle waveform whose period is 24 hours, amplitude
     * is half the milliseconds in a day and begins at currentTimeMillis() - timestamp, then offset
     * by the time difference / 48 to diminish older history items by an hour for every day old. 48
     * is used because the triangle wave is half amplitude (1 / 2) * (1 / 24) = 1 / 48.
     *
     * @param db    The SQL db
     * @param limit Maximum result size
     * @return Cursor
     */
    private static Cursor getHistoryByTime(SQLiteDatabase db, int limit) {
        final long now = System.currentTimeMillis();
        final long MS_24_DAYS_AGO = now - 2073600000L;
        String sql = "SELECT record, MAX(ABS((" + now + " - timestamp) % 86400000 - 43200000) - (" + now + " - timestamp) / 48 ) AS value" +
                " FROM history" +
                " WHERE timestamp > " + MS_24_DAYS_AGO +
                " GROUP BY record " +
                " ORDER BY value DESC " +
                " LIMIT " + limit;
        return db.rawQuery(sql, null);
    }


    /**
     * Retrieve previous query history
     *
     * @param context android context
     * @param limit   max number of items to retrieve
     * @return records with number of use
     */
    public static List<ValuedHistoryRecord> getHistory(Context context, int limit, HistoryMode historyMode) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        List<ValuedHistoryRecord> records;

        SQLiteDatabase db = recoveryDb;

        Cursor cursor;
        switch (historyMode) {
            case FRECENCY:
                cursor = getHistoryByFrecency(db, limit);
                break;
            case FREQUENCY:
                cursor = getHistoryByFrequency(db, limit);
                break;
            case ADAPTIVE:
                cursor = getHistoryByAdaptive(db, 36, limit);
                break;
            case TIME:
                cursor = getHistoryByTime(db, limit);
                break;
            case ALPHABETICALLY:
            case RECENCY:
                cursor = getHistoryByRecency(db, limit);
                break;
            default:
                cursor = getHistoryByRecency(db, limit);
                Log.e(TAG, "Fallback to 'recency' for unknown history mode " + historyMode);
                break;
        }

        records = readCursor(cursor);
        cursor.close();

        return records;

        });
    }


    /**
     * Strict RECENCY history read used by the visible Home timeline. This variant is cancellable so
     * touching the Vertical List can abort SQLite itself instead of merely interrupting Java after
     * the query has already consumed CPU/I/O.
     */
    public static List<ValuedHistoryRecord> getHistoryByRecency(
            Context context, int limit, CancellationSignal cancellationSignal) {
        return DatabaseRecovery.run(context, recoveryDb -> {
            // Keep the cancellable Home-timeline read semantically identical to the normal RECENCY
            // path: one row per history identity, ordered by that identity's newest launch.
            Cursor cursor = recoveryDb.rawQuery(
                    "SELECT record, 1, MAX(timeStamp), MAX(_id) FROM history "
                            + "GROUP BY record ORDER BY MAX(_id) DESC LIMIT ?",
                    new String[]{Integer.toString(limit)}, cancellationSignal);
            return readCursor(cursor);
        });
    }

    /**
     * Return the most frequently launched application ids for the Pixel favorites bar.
     *
     * This is a foreground, on-demand read only. It does not start a tracker, service, job or
     * background worker. Filtering to app:// ids in SQL also avoids resolving notification,
     * shortcut and contact history that can never become an app suggestion.
     */
    public static List<ValuedHistoryRecord> getMostUsedAppHistory(Context context, int limit) {
        final int safeLimit = Math.max(1, Math.min(80, limit));
        return DatabaseRecovery.run(context, recoveryDb -> {
            String sql = "SELECT record, COUNT(*) AS launch_count FROM history "
                    + "WHERE record LIKE 'app://%' "
                    + "GROUP BY record "
                    + "ORDER BY launch_count DESC, MAX(timeStamp) DESC "
                    + "LIMIT " + safeLimit;
            try (Cursor cursor = recoveryDb.rawQuery(sql, null)) {
                return readCursor(cursor);
            }
        });
    }


    /**
     * Retrieve history size
     *
     * @param context android context
     * @return total number of use for the application
     */
    public static int getHistoryLength(Context context) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;

        // Cursor query (boolean distinct, String table, String[] columns,
        // String selection, String[] selectionArgs, String groupBy, String
        // having, String orderBy, String limit)
        try (Cursor cursor = db.query(false, "history", new String[]{"COUNT(*)"}, null, null,
                null, null, null, null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }

        });
    }

    /**
     * Retrieve previously selected items for the query
     *
     * @param context android context
     * @param query   query to run
     * @return records with number of use
     */
    public static List<ValuedHistoryRecord> getPreviousResultsForQuery(Context context,
                                                                       String query) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        List<ValuedHistoryRecord> records;
        SQLiteDatabase db = recoveryDb;

        // Cursor query (String table, String[] columns, String selection,
        // String[] selectionArgs, String groupBy, String having, String
        // orderBy)
        Cursor cursor = db.query("history", new String[]{"record", "COUNT(*) AS count"},
                "query LIKE ?", new String[]{query + "%"}, "record", null, "COUNT(*) DESC", "10");
        records = readCursor(cursor);
        cursor.close();
        return records;

        });
    }

    /**
     * Insert or update a shortcut into DB.
     *
     * @param context
     * @param shortcut
     * @return true, if shortcut has changed
     */
    public static boolean insertShortcut(Context context, ShortcutRecord shortcut) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;
        String targetPackage = TextUtils.isEmpty(shortcut.targetPackage)
                ? "" : shortcut.targetPackage;

        boolean existing = false;
        boolean changed = true;
        try (Cursor cursor = db.query("shortcuts",
                new String[]{"name", "target_package"},
                "package = ? AND intent_uri = ?",
                new String[]{shortcut.packageName, shortcut.intentUri},
                null, null, null, "1")) {
            if (cursor.moveToFirst()) {
                existing = true;
                changed = !TextUtils.equals(shortcut.name, cursor.getString(0))
                        || !TextUtils.equals(targetPackage, cursor.getString(1));
            }
        }
        if (existing && !changed) return false;

        ContentValues values = new ContentValues();
        values.put("name", shortcut.name);
        values.put("package", shortcut.packageName);
        values.put("icon", (String) null); // Legacy field (for shortcuts before Oreo), not used anymore
        values.put("icon_blob", (String) null); // Another legacy field (icon is dynamically retrieved)
        values.put("intent_uri", shortcut.intentUri);
        values.put("target_package", targetPackage);

        int rowsAffected = db.update("shortcuts", values,
                "package = ? AND intent_uri = ?",
                new String[]{shortcut.packageName, shortcut.intentUri});
        if (rowsAffected == 0) db.insert("shortcuts", null, values);
        return true;

        });
    }

    /**
     * Remove a shortcut from DB.
     *
     * @param context
     * @param packageName
     * @param intentUri
     * @return true, if shortcut was removed
     */
    public static boolean removeShortcut(Context context, String packageName, String intentUri) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;
        int rowsAffected = db.delete("shortcuts", "package = ? AND intent_uri = ?", new String[]{packageName, intentUri});
        return rowsAffected > 0;

        });
    }

    public static void addCustomAppName(Context context, String componentName, String newName) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;

        long id;
        String sql = "INSERT OR ABORT INTO custom_apps(\"name\", \"component_name\", \"custom_flags\") VALUES (?,?,?)";
        try {
            SQLiteStatement statement = db.compileStatement(sql);
            statement.bindString(1, newName);
            statement.bindString(2, componentName);
            statement.bindLong(3, AppRecord.FLAG_CUSTOM_NAME);
            id = statement.executeInsert();
            statement.close();
        } catch (Exception e) {
            id = -1;
        }
        if (id == -1) {
            sql = "UPDATE custom_apps SET name=?,custom_flags=custom_flags|? WHERE component_name=?";
            try {
                SQLiteStatement statement = db.compileStatement(sql);
                statement.bindString(1, newName);
                statement.bindLong(2, AppRecord.FLAG_CUSTOM_NAME);
                statement.bindString(3, componentName);
                int count = statement.executeUpdateDelete();
                if (count != 1) {
                    Log.e(TAG, "Update name count = " + count);
                }
                statement.close();
            } catch (Exception e) {
                Log.e(TAG, "Insert or Update custom app name", e);
            }
        }

        });
    }


    @Nullable
    private static AppRecord getAppRecord(SQLiteDatabase db, String componentName) {
        String[] selArgs = new String[]{componentName};
        String[] columns = new String[]{"_id", "name", "component_name", "custom_flags"};
        try (Cursor cursor = db.query("custom_apps", columns,
                "component_name=?", selArgs, null, null, null)) {
            if (cursor.moveToNext()) {
                AppRecord entry = new AppRecord();

                entry.dbId = cursor.getLong(0);
                entry.name = cursor.getString(1);
                entry.componentName = cursor.getString(2);
                entry.flags = cursor.getInt(3);

                return entry;
            }
        }
        return null;
    }

    @Deprecated
    public static long removeCustomAppIcon(Context context, String componentName) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;
        AppRecord app = getAppRecord(db, componentName);
        if (app == null)
            return 0L;

        if (app.hasCustomName()) {
            // app has a custom name, just remove the custom icon
            String sql = "UPDATE custom_apps SET custom_flags=custom_flags&~? WHERE component_name=?";
            try {
                SQLiteStatement statement = db.compileStatement(sql);
                statement.bindLong(1, AppRecord.FLAG_CUSTOM_ICON);
                statement.bindString(2, componentName);
                int count = statement.executeUpdateDelete();
                if (count != 1) {
                    Log.e(TAG, "Update `custom_flags` returned count=" + count);
                }
                statement.close();
            } catch (Exception e) {
                Log.e(TAG, "remove custom app icon", e);
            }
        } else {
            // nothing custom about this app anymore, remove entry
            db.delete("custom_apps", "_id=?", new String[]{String.valueOf(app.dbId)});
        }

        return app.dbId;

        });
    }

    public static void removeCustomAppName(Context context, String componentName) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;
        AppRecord app = getAppRecord(db, componentName);
        if (app == null)
            return;

        if (app.hasCustomIcon()) {
            // app has a custom icon, just remove the custom name
            String sql = "UPDATE custom_apps SET custom_flags=custom_flags&~? WHERE component_name=?";
            try {
                SQLiteStatement statement = db.compileStatement(sql);
                statement.bindLong(1, AppRecord.FLAG_CUSTOM_NAME);
                statement.bindString(2, componentName);
                int count = statement.executeUpdateDelete();
                if (count != 1) {
                    Log.e(TAG, "Update `custom_flags` returned count=" + count);
                }
                statement.close();
            } catch (Exception e) {
                Log.e(TAG, "remove custom app icon", e);
            }
        } else {
            // nothing custom about this app anymore, remove entry
            db.delete("custom_apps", "_id=?", new String[]{String.valueOf(app.dbId)});
        }

        });
    }

    public static Map<String, AppRecord> getCustomAppData(Context context) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        Map<String, AppRecord> records;
        SQLiteDatabase db = recoveryDb;
        try (Cursor cursor = db.query("custom_apps", new String[]{"_id", "name", "component_name", "custom_flags"},
                null, null, null, null, null)) {
            records = new HashMap<>(cursor.getCount());
            while (cursor.moveToNext()) {
                AppRecord entry = new AppRecord();

                entry.dbId = cursor.getInt(0);
                entry.name = cursor.getString(1);
                entry.componentName = cursor.getString(2);
                entry.flags = cursor.getInt(3);

                records.put(entry.componentName, entry);
            }
        }

        return records;

        });
    }

    /**
     * Retrieve a list of all shortcuts for current package name, without icons.
     */
    public static List<ShortcutRecord> getShortcuts(Context context, String packageName) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;

        // Cursor query (String table, String[] columns, String selection,
        // String[] selectionArgs, String groupBy, String having, String
        // orderBy)
        try (Cursor cursor = db.query("shortcuts",
                new String[]{"_id", "name", "package", "intent_uri", "target_package"},
                "package = ?", new String[]{packageName}, null, null, null)) {
            cursor.moveToFirst();

            List<ShortcutRecord> records = new ArrayList<>();
            while (!cursor.isAfterLast()) {
                ShortcutRecord entry = new ShortcutRecord();

                entry.dbId = cursor.getInt(0);
                entry.name = cursor.getString(1);
                entry.packageName = cursor.getString(2);
                entry.intentUri = cursor.getString(3);
                entry.targetPackage = emptyToNull(cursor.getString(4));

                records.add(entry);
                cursor.moveToNext();
            }
            return records;
        }

        });
    }

    /**
     * Retrieve a list of all shortcuts, without icons.
     */
    public static List<ShortcutRecord> getShortcuts(Context context) {
        return getShortcuts(context, (CancellationSignal) null);
    }

    public static List<ShortcutRecord> getShortcuts(
            Context context, @Nullable CancellationSignal cancellationSignal) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;

        Cursor cursor = cancellationSignal == null
                ? db.query("shortcuts",
                        new String[]{"_id", "name", "package", "intent_uri", "target_package"},
                        null, null, null, null, null)
                : db.query(false, "shortcuts",
                        new String[]{"_id", "name", "package", "intent_uri", "target_package"},
                        null, null, null, null, null, null, cancellationSignal);
        try (Cursor closeable = cursor) {
            closeable.moveToFirst();

            List<ShortcutRecord> records = new ArrayList<>(closeable.getCount());
            while (!closeable.isAfterLast()) {
                if (cancellationSignal != null) cancellationSignal.throwIfCanceled();
                ShortcutRecord entry = new ShortcutRecord();

                entry.dbId = closeable.getInt(0);
                entry.name = closeable.getString(1);
                entry.packageName = closeable.getString(2);
                entry.intentUri = closeable.getString(3);
                entry.targetPackage = emptyToNull(closeable.getString(4));

                records.add(entry);
                closeable.moveToNext();
            }
            return records;
        }

        });
    }

    /**
     * Remove shortcuts for a given package name
     */
    public static void removeShortcuts(Context context, String packageName) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;

        // remove shortcuts
        db.delete("shortcuts", "package LIKE ?", new String[]{"%" + packageName + "%"});

        });
    }

    public static void removeAllShortcuts(Context context) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;
        // delete whole table
        db.delete("shortcuts", null, null);

        });
    }

    /**
     * Insert new tags for given id
     *
     * @param context android context
     * @param tag     tag to insert
     * @param record  record to insert
     */
    public static void insertTagsForId(Context context, String tag, String record) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;
        ContentValues values = new ContentValues();
        values.put("tag", tag);
        values.put("record", record);
        db.insert("tags", null, values);

        });
    }


    /**
     * Delete
     *
     * @param context android context
     * @param record  record to delete
     */
    public static void deleteTagsForId(Context context, String record) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;

        db.delete("tags", "record = ?", new String[]{record});

        });
    }

    /**
     * Delete all tags
     *
     * @param context android context
     */
    public static void deleteTags(Context context) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;

        db.execSQL("DELETE FROM tags;");

        });
    }

    public static Map<String, String> loadTags(Context context) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        Map<String, String> records = new HashMap<>();
        SQLiteDatabase db = recoveryDb;

        Cursor cursor = db.query("tags", new String[]{"record", "tag"}, null, null, null, null, null);

        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String id = cursor.getString(0);
            String tags = cursor.getString(1);
            records.put(id, tags);
            cursor.moveToNext();
        }
        cursor.close();
        return records;

        });
    }

    public static void upsertAppSourceMetadata(
            @NonNull Context context, @NonNull AppSourceMetadataRecord record) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
            ContentValues values = new ContentValues();
            values.put("package", record.packageName);
            values.put("source", record.source);
            values.put("installer_package", record.installerPackage);
            values.put("title", record.title);
            values.put("description", record.description);
            values.put("source_url", record.sourceUrl);
            values.put("fetched_at", record.fetchedAt);
            values.put("last_error", record.lastError);

            int updated = recoveryDb.update(
                    "app_source_metadata", values, "package = ?",
                    new String[]{record.packageName});
            if (updated == 0) recoveryDb.insert("app_source_metadata", null, values);
        });
    }

    @NonNull
    public static Map<String, AppSourceMetadataRecord> getAppSourceMetadata(
            @NonNull Context context) {
        return DatabaseRecovery.run(context, recoveryDb -> {
            Map<String, AppSourceMetadataRecord> records = new HashMap<>();
            String[] columns = {
                    "package", "source", "installer_package", "title",
                    "description", "source_url", "fetched_at", "last_error"
            };
            try (Cursor cursor = recoveryDb.query(
                    "app_source_metadata", columns,
                    null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    AppSourceMetadataRecord record = new AppSourceMetadataRecord();
                    record.packageName = cursor.getString(0);
                    record.source = cursor.getString(1);
                    record.installerPackage = cursor.getString(2);
                    record.title = cursor.getString(3);
                    record.description = cursor.getString(4);
                    record.sourceUrl = cursor.getString(5);
                    record.fetchedAt = cursor.getLong(6);
                    record.lastError = cursor.getString(7);
                    records.put(record.packageName, record);
                }
            }
            return records;
        });
    }

    public static int getAppSourceMetadataCount(@NonNull Context context) {
        return DatabaseRecovery.run(context, recoveryDb -> {
            try (Cursor cursor = recoveryDb.rawQuery(
                    "SELECT COUNT(*) FROM app_source_metadata WHERE description <> ''", null)) {
                return cursor.moveToFirst() ? cursor.getInt(0) : 0;
            }
        });
    }

    public static int getAppSourceMetadataTotalCount(@NonNull Context context) {
        return DatabaseRecovery.run(context, recoveryDb -> {
            try (Cursor cursor = recoveryDb.rawQuery(
                    "SELECT COUNT(*) FROM app_source_metadata", null)) {
                return cursor.moveToFirst() ? cursor.getInt(0) : 0;
            }
        });
    }

    public static void pruneAppSourceMetadata(
            @NonNull Context context, @NonNull java.util.Set<String> installedPackages) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
            if (installedPackages.isEmpty()) {
                recoveryDb.delete("app_source_metadata", null, null);
                return;
            }

            StringBuilder placeholders = new StringBuilder();
            String[] args = new String[installedPackages.size()];
            int index = 0;
            for (String packageName : installedPackages) {
                if (index > 0) placeholders.append(',');
                placeholders.append('?');
                args[index++] = packageName;
            }
            recoveryDb.delete(
                    "app_source_metadata",
                    "package NOT IN (" + placeholders + ")",
                    args);
        });
    }

    public static void insertSemanticActivity(
            @NonNull Context context, @NonNull SemanticActivityRecord record) {
        DatabaseRecovery.runVoid(context, recoveryDb ->
                recoveryDb.insert("semantic_activity_log", null, semanticActivityValues(record)));
    }

    public static void insertSemanticActivities(
            @NonNull Context context, @NonNull List<SemanticActivityRecord> records) {
        if (records.isEmpty()) return;
        DatabaseRecovery.runVoid(context, recoveryDb -> {
            recoveryDb.beginTransaction();
            try {
                for (SemanticActivityRecord record : records) {
                    if (record == null) continue;
                    recoveryDb.insert("semantic_activity_log", null, semanticActivityValues(record));
                }
                recoveryDb.execSQL(
                        "DELETE FROM semantic_activity_log WHERE _id NOT IN "
                                + "(SELECT _id FROM semantic_activity_log ORDER BY _id DESC LIMIT 10000)");
                recoveryDb.setTransactionSuccessful();
            } finally {
                recoveryDb.endTransaction();
            }
        });
    }

    @NonNull
    public static List<SemanticActivityRecord> getSemanticActivity(
            @NonNull Context context, int requestedLimit) {
        final int limit = Math.max(1, Math.min(10000, requestedLimit));
        return DatabaseRecovery.run(context, recoveryDb -> {
            List<SemanticActivityRecord> records = new ArrayList<>();
            String[] columns = {
                    "_id", "event_time", "event_type", "session_id",
                    "package", "app_name", "source", "details"
            };
            try (Cursor cursor = recoveryDb.query(
                    "semantic_activity_log",
                    columns,
                    null, null, null, null,
                    "event_time DESC, _id DESC",
                    Integer.toString(limit))) {
                while (cursor.moveToNext()) {
                    SemanticActivityRecord record = new SemanticActivityRecord();
                    record.id = cursor.getLong(0);
                    record.eventTime = cursor.getLong(1);
                    record.eventType = cursor.getString(2);
                    record.sessionId = cursor.getString(3);
                    record.packageName = cursor.getString(4);
                    record.appName = cursor.getString(5);
                    record.source = cursor.getString(6);
                    record.details = cursor.getString(7);
                    records.add(record);
                }
            }
            return records;
        });
    }

    public static void clearSemanticActivity(@NonNull Context context) {
        DatabaseRecovery.runVoid(context,
                recoveryDb -> recoveryDb.delete("semantic_activity_log", null, null));
    }

    private static ContentValues semanticActivityValues(@NonNull SemanticActivityRecord record) {
        ContentValues values = new ContentValues();
        values.put("event_time", record.eventTime);
        values.put("event_type", record.eventType);
        values.put("session_id", record.sessionId);
        values.put("package", record.packageName);
        values.put("app_name", record.appName);
        values.put("source", record.source);
        values.put("details", record.details);
        return values;
    }

    public static void initDatabase(Context context) {
        DatabaseRecovery.runVoid(context, recoveryDb -> { });
    }

    public static Map<String, ComponentName> getCustomComponents(@NonNull Context context) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        Map<String, ComponentName> components = new HashMap<>();
        SQLiteDatabase db = recoveryDb;
        try (Cursor cursor = db.query("custom_components", new String[]{"id", "package", "class"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String pck = cursor.getString(1);
                String cls = cursor.getString(2);
                ComponentName componentName = new ComponentName(pck, cls);
                components.put(id, componentName);
            }
        }

        return components;

        });
    }

    public static void setCustomComponent(@NonNull Context context, @NonNull String id, @Nullable ComponentName componentName) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;

        if (componentName == null) {
            db.delete("custom_components", "id = ?", new String[]{id});
        } else {
            ContentValues values = new ContentValues();
            values.put("id", id);
            values.put("package", componentName.getPackageName());
            values.put("class", componentName.getClassName());

            // do not add duplicate shortcuts
            int rowsAffected = db.update("custom_components", values, "id = ?", new String[]{id});
            if (rowsAffected == 0) {
                db.insert("custom_components", null, values);
            }
        }

        });
    }

    public static void removeAllCustomComponents(Context context) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        SQLiteDatabase db = recoveryDb;
        // delete whole table
        db.delete("custom_components", null, null);


        });
    }
}
