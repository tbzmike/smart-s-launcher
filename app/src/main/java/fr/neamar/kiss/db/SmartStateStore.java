package fr.neamar.kiss.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Persistent Smart S state that must survive provider reloads and app freezes. */
public final class SmartStateStore {
    private static volatile SQLiteDatabase database;

    private SmartStateStore() {}

    private static SQLiteDatabase db(Context context) {
        if (database == null) {
            synchronized (SmartStateStore.class) {
                if (database == null) database = new DB(context.getApplicationContext()).getWritableDatabase();
            }
        }
        return database;
    }

    public static void rememberApp(@NonNull Context context, @NonNull String packageName,
                                   @NonNull String activityName, @NonNull String label, long userSerial) {
        ContentValues values = new ContentValues();
        values.put("package", packageName);
        values.put("class", activityName);
        values.put("label", label);
        values.put("user_serial", userSerial);
        SQLiteDatabase database = db(context);
        int rows = database.update("app_catalog", values,
                "package=? AND class=? AND user_serial=?",
                new String[]{packageName, activityName, Long.toString(userSerial)});
        if (rows == 0) database.insert("app_catalog", null, values);
    }

    public static void forgetPackage(@NonNull Context context, @NonNull String packageName) {
        db(context).delete("app_catalog", "package=?", new String[]{packageName});
    }

    @NonNull
    public static List<AppCatalogRecord> getRememberedApps(@NonNull Context context, long userSerial) {
        List<AppCatalogRecord> result = new ArrayList<>();
        try (Cursor cursor = db(context).query("app_catalog",
                new String[]{"package", "class", "label", "user_serial"},
                "user_serial=?", new String[]{Long.toString(userSerial)}, null, null, "label COLLATE NOCASE")) {
            while (cursor.moveToNext()) {
                AppCatalogRecord record = new AppCatalogRecord();
                record.packageName = cursor.getString(0);
                record.activityName = cursor.getString(1);
                record.label = cursor.getString(2);
                record.userSerial = cursor.getLong(3);
                result.add(record);
            }
        }
        return result;
    }

    public static void saveNotification(@NonNull Context context, @NonNull String notificationId,
                                        @NonNull String packageName, @NonNull String appName,
                                        @Nullable String title, @Nullable String body, long postTime) {
        ContentValues values = new ContentValues();
        values.put("notification_id", notificationId);
        values.put("package", packageName);
        values.put("app_name", appName);
        values.put("title", title == null ? "" : title);
        values.put("body", body == null ? "" : body);
        values.put("post_time", postTime);
        // No item-count pruning: history grows until the user/device storage policy intervenes.
        db(context).insert("notification_history", null, values);
    }

    @NonNull
    public static List<String[]> getNotificationApps(@NonNull Context context) {
        List<String[]> result = new ArrayList<>();
        try (Cursor cursor = db(context).rawQuery(
                "SELECT package, MAX(app_name) FROM notification_history GROUP BY package ORDER BY MAX(post_time) DESC", null)) {
            while (cursor.moveToNext()) result.add(new String[]{cursor.getString(0), cursor.getString(1)});
        }
        return result;
    }

    @NonNull
    public static List<NotificationHistoryRecord> queryNotifications(@NonNull Context context,
                                                                     @Nullable String packageName,
                                                                     @Nullable List<String> terms,
                                                                     int limit) {
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();
        if (packageName != null && !packageName.isEmpty()) {
            where.append("package=?");
            args.add(packageName);
        }
        if (terms != null && !terms.isEmpty()) {
            if (where.length() > 0) where.append(" AND ");
            where.append('(');
            for (int i = 0; i < terms.size(); i++) {
                if (i > 0) where.append(" OR ");
                where.append("app_name LIKE ? OR title LIKE ? OR body LIKE ?");
                String like = "%" + terms.get(i) + "%";
                args.add(like); args.add(like); args.add(like);
            }
            where.append(')');
        }

        List<NotificationHistoryRecord> result = new ArrayList<>();
        String limitText = limit > 0 ? Integer.toString(limit) : null;
        try (Cursor cursor = db(context).query("notification_history",
                new String[]{"_id", "notification_id", "package", "app_name", "title", "body", "post_time"},
                where.length() == 0 ? null : where.toString(),
                args.isEmpty() ? null : args.toArray(new String[0]),
                null, null, "post_time DESC", limitText)) {
            while (cursor.moveToNext()) {
                NotificationHistoryRecord record = new NotificationHistoryRecord();
                record.dbId = cursor.getLong(0);
                record.notificationId = cursor.getString(1);
                record.packageName = cursor.getString(2);
                record.appName = cursor.getString(3);
                record.title = cursor.getString(4);
                record.text = cursor.getString(5);
                record.postTime = cursor.getLong(6);
                result.add(record);
            }
        }
        return result;
    }
}
