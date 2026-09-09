package fr.neamar.kiss.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Exact lookup/forward scan helpers for the launcher notification timeline. */
public final class NotificationTimelineStore {
    private NotificationTimelineStore() { }

    private static SQLiteDatabase db(Context context) {
        return DatabaseRecovery.getDatabase(context);
    }

    @Nullable
    public static NotificationHistoryRecord findLatest(@NonNull Context context,
                                                       @NonNull String notificationId) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        try (Cursor cursor = recoveryDb.query("notification_history",
                new String[]{"_id", "notification_id", "package", "app_name", "title", "body", "post_time", "is_permanent", "shortcut_id", "user_serial", "route_uri"},
                "notification_id=?", new String[]{notificationId}, null, null,
                "post_time DESC, _id DESC", "1")) {
            if (!cursor.moveToFirst()) return null;
            return read(cursor);
        }

        });
    }

    @Nullable
    public static NotificationHistoryRecord findByDbId(@NonNull Context context, long dbId) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        if (dbId <= 0L) return null;
        try (Cursor cursor = recoveryDb.query("notification_history",
                new String[]{"_id", "notification_id", "package", "app_name", "title", "body",
                        "post_time", "is_permanent", "shortcut_id", "user_serial", "route_uri"},
                "_id=?", new String[]{Long.toString(dbId)}, null, null, null, "1")) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }

        });
    }

    /**
     * Reads persisted notifications newer than the last launcher timeline scan. The limit is a
     * safety valve for an extremely long launcher absence; callers advance the watermark and run
     * again on subsequent resumes when necessary.
     */
    @NonNull
    public static List<NotificationHistoryRecord> queryAfter(@NonNull Context context,
                                                              long afterTimestamp,
                                                              int limit) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        List<NotificationHistoryRecord> result = new ArrayList<>();
        String limitText = limit > 0 ? Integer.toString(limit) : null;
        try (Cursor cursor = recoveryDb.query("notification_history",
                new String[]{"_id", "notification_id", "package", "app_name", "title", "body", "post_time", "is_permanent", "shortcut_id", "user_serial", "route_uri"},
                "post_time>?", new String[]{Long.toString(Math.max(0L, afterTimestamp))},
                null, null, "post_time ASC, _id ASC", limitText)) {
            while (cursor.moveToNext()) result.add(read(cursor));
        }
        return result;

        });
    }

    private static NotificationHistoryRecord read(Cursor cursor) {
        NotificationHistoryRecord record = new NotificationHistoryRecord();
        record.dbId = cursor.getLong(0);
        record.notificationId = cursor.getString(1);
        record.packageName = cursor.getString(2);
        record.appName = cursor.getString(3);
        record.title = cursor.getString(4);
        record.text = cursor.getString(5);
        record.postTime = cursor.getLong(6);
        record.permanent = cursor.getInt(7) != 0;
        record.shortcutId = cursor.getString(8);
        record.userSerial = cursor.getLong(9);
        record.routeUri = cursor.getString(10);
        return record;
    }
}
