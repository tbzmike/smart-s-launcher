package fr.neamar.kiss.backup;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;

/**
 * Merges user-visible notification and app-usage history from the installed data set into a
 * staged backup database before the staged databases replace the live directory.
 *
 * The backup remains the primary copy: records already in it are retained, and records that
 * exist only on the device are added using their original event timestamps. Stable event keys
 * prevent repeat restores from duplicating timeline entries.
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
        try {
            target = SQLiteDatabase.openDatabase(backupDatabase.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            target.execSQL("ATTACH DATABASE ? AS " + LIVE_ALIAS,
                    new Object[]{liveDatabase.getAbsolutePath()});
            target.beginTransaction();
            try {
                if ("kiss.s3db".equals(databaseName)) {
                    mergeNotificationHistory(target);
                    mergeLaunchHistory(target);
                } else if ("smart_s_app_usage.db".equals(databaseName)) {
                    mergeUsageTimeline(target);
                    mergeDailyUsage(target);
                    mergeDailyPhoneState(target);
                    mergePackageState(target);
                    mergeMeta(target);
                }
                target.setTransactionSuccessful();
            } finally {
                target.endTransaction();
            }
            target.execSQL("DETACH DATABASE " + LIVE_ALIAS);
            target = null;
        } catch (RuntimeException failure) {
            throw new IOException("Unable to merge existing " + databaseName
                    + " into the restored backup", failure);
        } finally {
            if (target != null) {
                try {
                    if (target.isOpen()) target.close();
                } catch (RuntimeException ignored) { }
            }
        }
    }

    private static void mergeNotificationHistory(SQLiteDatabase db) {
        db.execSQL("INSERT INTO main.notification_history "
                + "(notification_id,package,app_name,title,body,post_time,is_permanent,"
                + "shortcut_id,user_serial,route_uri,pending_intent_token,locus_id) "
                + "SELECT c.notification_id,c.package,c.app_name,c.title,c.body,c.post_time,"
                + "c.is_permanent,c.shortcut_id,c.user_serial,c.route_uri,"
                + "c.pending_intent_token,c.locus_id FROM " + LIVE_ALIAS
                + ".notification_history c WHERE NOT EXISTS (SELECT 1 FROM main.notification_history b "
                + "WHERE b.notification_id=c.notification_id AND b.post_time=c.post_time)");
    }

    private static void mergeLaunchHistory(SQLiteDatabase db) {
        db.execSQL("INSERT INTO main.history (query,record,timeStamp) SELECT c.query,c.record,c.timeStamp "
                + "FROM " + LIVE_ALIAS + ".history c WHERE NOT EXISTS "
                + "(SELECT 1 FROM main.history b WHERE b.record=c.record AND b.timeStamp=c.timeStamp)");
    }

    private static void mergeUsageTimeline(SQLiteDatabase db) {
        db.execSQL("INSERT OR IGNORE INTO main.timeline "
                + "(event_key,start_ms,end_ms,kind,package_name,app_label,duration_ms,is_system,detail,source,source_uri) "
                + "SELECT event_key,start_ms,end_ms,kind,package_name,app_label,duration_ms,is_system,"
                + "detail,source,source_uri FROM " + LIVE_ALIAS + ".timeline");
        db.execSQL("UPDATE main.timeline SET "
                + "end_ms=MAX(end_ms,COALESCE((SELECT c.end_ms FROM " + LIVE_ALIAS
                + ".timeline c WHERE c.event_key=main.timeline.event_key),end_ms)),"
                + "duration_ms=MAX(duration_ms,COALESCE((SELECT c.duration_ms FROM " + LIVE_ALIAS
                + ".timeline c WHERE c.event_key=main.timeline.event_key),duration_ms)) "
                + "WHERE EXISTS (SELECT 1 FROM " + LIVE_ALIAS
                + ".timeline c WHERE c.event_key=main.timeline.event_key)");
    }

    private static void mergeDailyUsage(SQLiteDatabase db) {
        db.execSQL("INSERT OR IGNORE INTO main.daily_usage "
                + "(day_ms,package_name,app_label,duration_ms,is_system) SELECT "
                + "day_ms,package_name,app_label,duration_ms,is_system FROM "
                + LIVE_ALIAS + ".daily_usage");
        db.execSQL("UPDATE main.daily_usage SET "
                + "duration_ms=MAX(duration_ms,COALESCE((SELECT c.duration_ms FROM " + LIVE_ALIAS
                + ".daily_usage c WHERE c.day_ms=main.daily_usage.day_ms "
                + "AND c.package_name=main.daily_usage.package_name),duration_ms)) "
                + "WHERE EXISTS (SELECT 1 FROM " + LIVE_ALIAS
                + ".daily_usage c WHERE c.day_ms=main.daily_usage.day_ms "
                + "AND c.package_name=main.daily_usage.package_name)");
    }

    private static void mergeDailyPhoneState(SQLiteDatabase db) {
        db.execSQL("INSERT OR IGNORE INTO main.daily_phone_state "
                + "(day_ms,screen_on_ms,screen_off_ms,unlock_count) SELECT "
                + "day_ms,screen_on_ms,screen_off_ms,unlock_count FROM "
                + LIVE_ALIAS + ".daily_phone_state");
        db.execSQL("UPDATE main.daily_phone_state SET "
                + "screen_on_ms=MAX(screen_on_ms,COALESCE((SELECT c.screen_on_ms FROM " + LIVE_ALIAS
                + ".daily_phone_state c WHERE c.day_ms=main.daily_phone_state.day_ms),screen_on_ms)),"
                + "screen_off_ms=MAX(screen_off_ms,COALESCE((SELECT c.screen_off_ms FROM " + LIVE_ALIAS
                + ".daily_phone_state c WHERE c.day_ms=main.daily_phone_state.day_ms),screen_off_ms)),"
                + "unlock_count=MAX(unlock_count,COALESCE((SELECT c.unlock_count FROM " + LIVE_ALIAS
                + ".daily_phone_state c WHERE c.day_ms=main.daily_phone_state.day_ms),unlock_count)) "
                + "WHERE EXISTS (SELECT 1 FROM " + LIVE_ALIAS
                + ".daily_phone_state c WHERE c.day_ms=main.daily_phone_state.day_ms)");
    }

    private static void mergePackageState(SQLiteDatabase db) {
        db.execSQL("INSERT OR IGNORE INTO main.package_state "
                + "(package_name,app_label,is_system,first_install_ms,last_update_ms,"
                + "installer_package,source,source_uri) SELECT package_name,app_label,is_system,"
                + "first_install_ms,last_update_ms,installer_package,source,source_uri FROM "
                + LIVE_ALIAS + ".package_state");
        db.execSQL("UPDATE main.package_state SET "
                + "first_install_ms=MIN(first_install_ms,COALESCE((SELECT c.first_install_ms FROM "
                + LIVE_ALIAS + ".package_state c WHERE c.package_name=main.package_state.package_name),first_install_ms)),"
                + "last_update_ms=MAX(last_update_ms,COALESCE((SELECT c.last_update_ms FROM "
                + LIVE_ALIAS + ".package_state c WHERE c.package_name=main.package_state.package_name),last_update_ms)) "
                + "WHERE EXISTS (SELECT 1 FROM " + LIVE_ALIAS
                + ".package_state c WHERE c.package_name=main.package_state.package_name)");
    }

    private static void mergeMeta(SQLiteDatabase db) {
        db.execSQL("INSERT OR IGNORE INTO main.meta(meta_key,meta_value) "
                + "SELECT meta_key,meta_value FROM " + LIVE_ALIAS + ".meta");
        db.execSQL("UPDATE main.meta SET meta_value=CAST(MAX(CAST(meta_value AS INTEGER),"
                + "COALESCE((SELECT CAST(c.meta_value AS INTEGER) FROM " + LIVE_ALIAS
                + ".meta c WHERE c.meta_key=main.meta.meta_key),CAST(meta_value AS INTEGER))) AS TEXT) "
                + "WHERE meta_key IN (SELECT meta_key FROM " + LIVE_ALIAS
                + ".meta WHERE meta_value GLOB '[0-9]*')");
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
