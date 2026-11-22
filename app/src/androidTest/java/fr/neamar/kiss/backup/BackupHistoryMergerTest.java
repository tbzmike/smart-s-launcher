package fr.neamar.kiss.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/** Verifies that restoring an older archive keeps both archived and subsequently collected history. */
@RunWith(AndroidJUnit4.class)
public final class BackupHistoryMergerTest {
    @Test public void notificationAndUsageHistoryAreMergedWithoutDuplicates() throws Exception {
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir();
        File backup = new File(directory, "history-merge-backup.db");
        File live = new File(directory, "history-merge-live.db");
        backup.delete();
        live.delete();
        try {
            createMainDatabase(backup);
            createMainDatabase(live);
            insertNotification(backup, "notif-old", 100L);
            insertNotification(backup, "notif-shared", 200L);
            insertHistory(backup, "app://example.repeat-launch", 1000L);
            insertNotification(live, "notif-new", 300L);
            insertNotification(live, "notif-shared", 200L);
            insertHistory(live, "app://example.repeat-launch", 2000L);

            BackupHistoryMerger.merge(backup, live, "kiss.s3db");
            assertEquals(3, count(backup, "notification_history"));
            assertEquals(5, count(backup, "history"));

            File usageBackup = new File(directory, "usage-merge-backup.db");
            File usageLive = new File(directory, "usage-merge-live.db");
            usageBackup.delete();
            usageLive.delete();
            try {
                createUsageDatabase(usageBackup);
                createUsageDatabase(usageLive);
                insertUsage(usageBackup, "usage-old", 100L, 5000L);
                insertUsage(usageLive, "usage-new", 300L, 12000L);
                insertUsage(usageLive, "usage-old", 100L, 5000L);
                insertDailyUsage(usageBackup, 1000L, "example.app", 6000L);
                insertDailyUsage(usageLive, 1000L, "example.app", 12000L);
                insertPhoneState(usageBackup, 1000L, 3000L, 4000L, 2);
                insertPhoneState(usageLive, 1000L, 5000L, 2000L, 4);
                insertPackageState(usageBackup, "known.install", 100L, 200L);
                insertPackageState(usageLive, "known.install", 0L, 300L);
                insertPackageState(usageBackup, "missing.install", 0L, 200L);
                insertPackageState(usageLive, "missing.install", 50L, 300L);
                insertMeta(usageBackup, "sync_cursor", 5L);
                insertMeta(usageLive, "sync_cursor", 9L);

                BackupHistoryMerger.merge(usageBackup, usageLive, "smart_s_app_usage.db");
                assertEquals(2, count(usageBackup, "timeline"));
                assertEquals(12000L, scalarLong(usageBackup,
                        "SELECT duration_ms FROM daily_usage WHERE day_ms=1000 AND package_name='example.app'"));
                assertEquals(5000L, scalarLong(usageBackup,
                        "SELECT screen_on_ms FROM daily_phone_state WHERE day_ms=1000"));
                assertEquals(4000L, scalarLong(usageBackup,
                        "SELECT screen_off_ms FROM daily_phone_state WHERE day_ms=1000"));
                assertEquals(4L, scalarLong(usageBackup,
                        "SELECT unlock_count FROM daily_phone_state WHERE day_ms=1000"));
                assertEquals(100L, scalarLong(usageBackup,
                        "SELECT first_install_ms FROM package_state WHERE package_name='known.install'"));
                assertEquals(300L, scalarLong(usageBackup,
                        "SELECT last_update_ms FROM package_state WHERE package_name='known.install'"));
                assertEquals(50L, scalarLong(usageBackup,
                        "SELECT first_install_ms FROM package_state WHERE package_name='missing.install'"));
                assertEquals(9L, scalarLong(usageBackup,
                        "SELECT CAST(meta_value AS INTEGER) FROM meta WHERE meta_key='sync_cursor'"));
                try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                        usageBackup.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                     Cursor cursor = db.rawQuery(
                             "SELECT event_key FROM timeline ORDER BY start_ms ASC", null)) {
                    assertTrue(cursor.moveToFirst());
                    assertEquals("usage-old", cursor.getString(0));
                    assertTrue(cursor.moveToNext());
                    assertEquals("usage-new", cursor.getString(0));
                }
            } finally {
                usageBackup.delete();
                usageLive.delete();
            }
        } finally {
            backup.delete();
            live.delete();
        }
    }

    @Test public void restoreDirectoryWiringMergesBothHistoryDatabases() throws Exception {
        File root = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File staged = new File(root, "restore-wiring-staged");
        File live = new File(root, "restore-wiring-live");
        deleteRecursively(staged);
        deleteRecursively(live);
        assertTrue(staged.mkdirs());
        assertTrue(live.mkdirs());
        try {
            File stagedMain = new File(staged, "kiss.s3db");
            File liveMain = new File(live, "kiss.s3db");
            createMainDatabase(stagedMain);
            createMainDatabase(liveMain);
            insertNotification(stagedMain, "archived", 100L);
            insertNotification(liveMain, "newer", 200L);

            File stagedUsage = new File(staged, "smart_s_app_usage.db");
            File liveUsage = new File(live, "smart_s_app_usage.db");
            createUsageDatabase(stagedUsage);
            createUsageDatabase(liveUsage);
            insertUsage(stagedUsage, "archived-usage", 100L, 5000L);
            insertUsage(liveUsage, "newer-usage", 200L, 9000L);

            FullBackupManager.mergeRestoredHistoryDatabases(live, staged);

            assertEquals(2, count(stagedMain, "notification_history"));
            assertEquals(2, count(stagedUsage, "timeline"));

            // Older archives may not contain the app-usage database at all. The restore path
            // must still bring forward the live database instead of silently omitting it.
            stagedUsage.delete();
            FullBackupManager.mergeRestoredHistoryDatabases(live, staged);
            assertEquals(1, count(stagedUsage, "timeline"));
            assertEquals(1L, scalarLong(stagedUsage,
                    "SELECT COUNT(*) FROM timeline WHERE event_key='newer-usage'"));
        } finally {
            deleteRecursively(staged);
            deleteRecursively(live);
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static void createMainDatabase(File file) {
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            db.execSQL("CREATE TABLE notification_history (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "notification_id TEXT NOT NULL,package TEXT NOT NULL,app_name TEXT NOT NULL,"
                    + "title TEXT NOT NULL DEFAULT '',body TEXT NOT NULL DEFAULT '',post_time INTEGER NOT NULL,"
                    + "is_permanent INTEGER NOT NULL DEFAULT 0,shortcut_id TEXT NOT NULL DEFAULT '',"
                    + "user_serial INTEGER NOT NULL DEFAULT -1,route_uri TEXT NOT NULL DEFAULT '',"
                    + "pending_intent_token TEXT NOT NULL DEFAULT '',locus_id TEXT NOT NULL DEFAULT '')");
            db.execSQL("CREATE TABLE history (_id INTEGER PRIMARY KEY AUTOINCREMENT,query TEXT,"
                    + "record TEXT NOT NULL,timeStamp INTEGER NOT NULL DEFAULT 0)");
        }
    }

    private static void createUsageDatabase(File file) {
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            db.execSQL("CREATE TABLE timeline (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "event_key TEXT NOT NULL UNIQUE,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL DEFAULT 0,"
                    + "kind TEXT NOT NULL,package_name TEXT,app_label TEXT,duration_ms INTEGER NOT NULL DEFAULT 0,"
                    + "is_system INTEGER NOT NULL DEFAULT 0,detail TEXT,source TEXT,source_uri TEXT)");
            db.execSQL("CREATE TABLE daily_usage (day_ms INTEGER NOT NULL,package_name TEXT NOT NULL,"
                    + "app_label TEXT,duration_ms INTEGER NOT NULL DEFAULT 0,is_system INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY(day_ms,package_name))");
            db.execSQL("CREATE TABLE daily_phone_state (day_ms INTEGER PRIMARY KEY,screen_on_ms INTEGER NOT NULL DEFAULT 0,"
                    + "screen_off_ms INTEGER NOT NULL DEFAULT 0,unlock_count INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE package_state (package_name TEXT PRIMARY KEY,app_label TEXT,"
                    + "is_system INTEGER NOT NULL DEFAULT 0,first_install_ms INTEGER NOT NULL DEFAULT 0,"
                    + "last_update_ms INTEGER NOT NULL DEFAULT 0,installer_package TEXT,source TEXT,source_uri TEXT)");
            db.execSQL("CREATE TABLE meta (meta_key TEXT PRIMARY KEY,meta_value TEXT)");
        }
    }

    private static void insertNotification(File file, String id, long time) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("INSERT INTO notification_history(notification_id,package,app_name,title,body,post_time) "
                    + "VALUES(?,?,?,?,?,?)", new Object[]{id, "example.app", "Example", id, "body", time});
            db.execSQL("INSERT INTO history(query,record,timeStamp) VALUES(NULL,?,?)",
                    new Object[]{"notification://" + id, time});
        }
    }

    private static void insertHistory(File file, String record, long time) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("INSERT INTO history(query,record,timeStamp) VALUES(NULL,?,?)",
                    new Object[]{record, time});
        }
    }

    private static void insertUsage(File file, String key, long time, long duration) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("INSERT INTO timeline(event_key,start_ms,kind,duration_ms) VALUES(?,?,?,?)",
                    new Object[]{key, time, "APP_USAGE", duration});
        }
    }

    private static void insertDailyUsage(File file, long day, String pkg, long duration) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("INSERT INTO daily_usage(day_ms,package_name,duration_ms) VALUES(?,?,?)",
                    new Object[]{day, pkg, duration});
        }
    }

    private static void insertPhoneState(File file, long day, long on, long off, int unlocks) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("INSERT INTO daily_phone_state(day_ms,screen_on_ms,screen_off_ms,unlock_count) "
                    + "VALUES(?,?,?,?)", new Object[]{day, on, off, unlocks});
        }
    }

    private static void insertPackageState(File file, String pkg, long first, long updated) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("INSERT INTO package_state(package_name,first_install_ms,last_update_ms) "
                    + "VALUES(?,?,?)", new Object[]{pkg, first, updated});
        }
    }

    private static void insertMeta(File file, String key, long value) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("INSERT INTO meta(meta_key,meta_value) VALUES(?,?)",
                    new Object[]{key, Long.toString(value)});
        }
    }

    private static long scalarLong(File file, String sql) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor cursor = db.rawQuery(sql, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private static int count(File file, String table) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }
}
