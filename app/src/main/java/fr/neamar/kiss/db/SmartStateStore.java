package fr.neamar.kiss.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteFullException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.utils.Log;

/** Persistent Smart S state that must survive provider reloads and app freezes. */
public final class SmartStateStore {
    private static final String TAG = SmartStateStore.class.getSimpleName();
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

        database.delete("app_catalog",
                "package=? AND user_serial=? AND class<>?",
                new String[]{packageName, Long.toString(userSerial), activityName});

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
        Set<String> seenPackages = new HashSet<>();
        List<Long> duplicateIds = new ArrayList<>();

        try (Cursor cursor = db(context).query("app_catalog",
                new String[]{"_id", "package", "class", "label", "user_serial"},
                "user_serial=?", new String[]{Long.toString(userSerial)}, null, null, "_id DESC")) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String packageName = cursor.getString(1);
                if (!seenPackages.add(packageName)) {
                    duplicateIds.add(id);
                    continue;
                }

                AppCatalogRecord record = new AppCatalogRecord();
                record.packageName = packageName;
                record.activityName = cursor.getString(2);
                record.label = cursor.getString(3);
                record.userSerial = cursor.getLong(4);
                result.add(record);
            }
        }

        SQLiteDatabase database = db(context);
        for (Long duplicateId : duplicateIds) {
            database.delete("app_catalog", "_id=?", new String[]{Long.toString(duplicateId)});
        }
        return result;
    }

    @NonNull
    public static List<String[]> getNotificationApps(@NonNull Context context) {
        List<String[]> result = new ArrayList<>();
        try (Cursor cursor = db(context).rawQuery(
                "SELECT package, MAX(app_name) FROM notification_history WHERE is_permanent=0 GROUP BY package ORDER BY MAX(post_time) DESC", null)) {
            while (cursor.moveToNext()) result.add(new String[]{cursor.getString(0), cursor.getString(1)});
        }
        return result;
    }

    public static void saveNotification(@NonNull Context context, @NonNull String notificationId,
                                        @NonNull String packageName, @NonNull String appName,
                                        @Nullable String title, @Nullable String body, long postTime,
                                        boolean permanent) {
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("enable-notification-history", false)) {
            return;
        }

        ContentValues values = new ContentValues();
        values.put("notification_id", notificationId);
        values.put("package", packageName);
        values.put("app_name", appName);
        values.put("title", title == null ? "" : title);
        values.put("body", body == null ? "" : body);
        values.put("post_time", postTime);
        values.put("is_permanent", permanent ? 1 : 0);
        try {
            SQLiteDatabase database = db(context);
            if (permanent) {
                ContentValues permanentState = new ContentValues(1);
                permanentState.put("is_permanent", 1);
                database.update("notification_history", permanentState,
                        "notification_id=?", new String[]{notificationId});
            }
            int rows = database.update("notification_history", values,
                    "notification_id=? AND post_time=?",
                    new String[]{notificationId, Long.toString(postTime)});
            if (rows == 0) database.insertOrThrow("notification_history", null, values);
        } catch (SQLiteFullException e) {
            Log.w(TAG, "Notification history reached available database storage", e);
        }
    }

    @NonNull
    public static List<NotificationHistoryRecord> queryNotifications(@NonNull Context context,
                                                                     @Nullable String packageName,
                                                                     @Nullable List<String> terms,
                                                                     int limit) {
        return queryNotifications(context, packageName, terms, null, limit);
    }

    @NonNull
    public static List<NotificationHistoryRecord> queryNotifications(@NonNull Context context,
                                                                     @Nullable String packageName,
                                                                     @Nullable List<String> terms,
                                                                     @Nullable Boolean permanent,
                                                                     int limit) {
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();
        if (packageName != null && !packageName.isEmpty()) {
            where.append("package=?");
            args.add(packageName);
        }
        if (permanent != null) {
            if (where.length() > 0) where.append(" AND ");
            where.append("is_permanent=?");
            args.add(permanent ? "1" : "0");
        }
        if (terms != null && !terms.isEmpty()) {
            if (where.length() > 0) where.append(" AND ");
            where.append('(');
            for (int i = 0; i < terms.size(); i++) {
                if (i > 0) where.append(" OR ");
                where.append("app_name LIKE ? OR title LIKE ? OR body LIKE ?");
                String like = "%" + terms.get(i) + "%";
                args.add(like);
                args.add(like);
                args.add(like);
            }
            where.append(')');
        }

        List<NotificationHistoryRecord> result = new ArrayList<>();
        String limitText = limit > 0 ? Integer.toString(limit) : null;
        try (Cursor cursor = db(context).query("notification_history",
                new String[]{"_id", "notification_id", "package", "app_name", "title", "body", "post_time", "is_permanent"},
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
                record.permanent = cursor.getInt(7) != 0;
                result.add(record);
            }
        }
        return result;
    }
}
