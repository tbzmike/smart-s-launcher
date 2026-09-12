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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.utils.Log;

/** Persistent Smart S state that must survive provider reloads and app freezes. */
public final class SmartStateStore {
    private static final String TAG = SmartStateStore.class.getSimpleName();
    private static final Object LATEST_NOTIFICATION_LOCK = new Object();
    private static volatile Map<String, NotificationHistoryRecord> latestNotificationsCache;

    private SmartStateStore() {}

    static void onDatabaseRecovered() {
        latestNotificationsCache = null;
    }

    private static SQLiteDatabase db(Context context) {
        return DatabaseRecovery.getDatabase(context);
    }

    public static void rememberApp(@NonNull Context context, @NonNull String packageName,
                                   @NonNull String activityName, @NonNull String label, long userSerial) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        ContentValues values = new ContentValues();
        values.put("package", packageName);
        values.put("class", activityName);
        values.put("label", label);
        if (userSerial >= 0L) values.put("user_serial", userSerial);
        SQLiteDatabase database = recoveryDb;

        database.delete("app_catalog",
                "package=? AND user_serial=? AND class<>?",
                new String[]{packageName, Long.toString(userSerial), activityName});

        int rows = database.update("app_catalog", values,
                "package=? AND class=? AND user_serial=?",
                new String[]{packageName, activityName, Long.toString(userSerial)});
        if (rows == 0) database.insert("app_catalog", null, values);

        });
    }

    public static void forgetPackage(@NonNull Context context, @NonNull String packageName) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        recoveryDb.delete("app_catalog", "package=?", new String[]{packageName});

        });
    }

    @NonNull
    public static List<AppCatalogRecord> getRememberedApps(@NonNull Context context, long userSerial) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        List<AppCatalogRecord> result = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        List<Long> duplicateIds = new ArrayList<>();

        try (Cursor cursor = recoveryDb.query("app_catalog",
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

        SQLiteDatabase database = recoveryDb;
        for (Long duplicateId : duplicateIds) {
            database.delete("app_catalog", "_id=?", new String[]{Long.toString(duplicateId)});
        }
        return result;

        });
    }

    @NonNull
    public static List<String[]> getNotificationApps(@NonNull Context context) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        List<String[]> result = new ArrayList<>();
        try (Cursor cursor = recoveryDb.rawQuery(
                "SELECT package, MAX(app_name) FROM notification_history WHERE is_permanent=0 GROUP BY package ORDER BY MAX(post_time) DESC", null)) {
            while (cursor.moveToNext()) result.add(new String[]{cursor.getString(0), cursor.getString(1)});
        }
        return result;

        });
    }

    /**
     * Return the newest stored notification for every package in one indexed database query.
     *
     * Vertical Cards used to issue one query per app on every history rebuild. Grouping the lookup
     * keeps the exact same newest-message behavior while avoiding repeated cursor creation and
     * database traversal on the main thread. A post-time tie is resolved by the newest row id.
     */
    @NonNull
    public static Map<String, NotificationHistoryRecord> queryLatestNotificationsByPackage(
            @NonNull Context context) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        Map<String, NotificationHistoryRecord> result = new LinkedHashMap<>();
        String sql = "SELECT n._id,n.notification_id,n.package,n.app_name,n.title,n.body,"
                + "n.post_time,n.is_permanent,n.shortcut_id,n.user_serial,n.route_uri,"
                + "n.pending_intent_token,n.locus_id FROM notification_history n INNER JOIN "
                + "(SELECT package,MAX(post_time) latest_time FROM notification_history "
                + "GROUP BY package) latest ON latest.package=n.package "
                + "AND latest.latest_time=n.post_time ORDER BY n.post_time DESC,n._id DESC";
        try (Cursor cursor = recoveryDb.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                NotificationHistoryRecord record = readNotificationRecord(cursor);
                if (record.packageName != null && !result.containsKey(record.packageName)) {
                    result.put(record.packageName, record);
                }
            }
        }
        latestNotificationsCache = new LinkedHashMap<>(result);
        return result;

        });
    }

    /** Fast O(1) lookup used by vertical-card binding after one grouped database scan. */
    @Nullable
    public static NotificationHistoryRecord latestNotificationForPackage(
            @NonNull Context context, @Nullable String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;
        Map<String, NotificationHistoryRecord> local = latestNotificationsCache;
        if (local == null) {
            synchronized (LATEST_NOTIFICATION_LOCK) {
                local = latestNotificationsCache;
                if (local == null) {
                    local = queryLatestNotificationsByPackage(context);
                    latestNotificationsCache = local;
                }
            }
        }
        return local.get(packageName);
    }

    public static void saveNotification(@NonNull Context context, @NonNull String notificationId,
                                        @NonNull String packageName, @NonNull String appName,
                                        @Nullable String title, @Nullable String body, long postTime,
                                        boolean permanent, @Nullable String shortcutId, long userSerial,
                                        @Nullable String routeUri, @Nullable String pendingIntentToken,
                                        @Nullable String locusId) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
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
        // Route fields are write-on-success for an existing exact notification event. Listener
        // reconnects and notification refreshes do not always expose every route again; writing an
        // empty refresh value here used to erase a previously captured direct-message destination.
        // New rows still receive the table defaults for fields Android did not expose.
        if (shortcutId != null && !shortcutId.isEmpty()) values.put("shortcut_id", shortcutId);
        values.put("user_serial", userSerial);
        if (routeUri != null && !routeUri.isEmpty()) values.put("route_uri", routeUri);
        if (pendingIntentToken != null && !pendingIntentToken.isEmpty()) {
            values.put("pending_intent_token", pendingIntentToken);
        }
        if (locusId != null && !locusId.isEmpty()) values.put("locus_id", locusId);
        try {
            SQLiteDatabase database = recoveryDb;
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
            latestNotificationsCache = null;
        } catch (SQLiteFullException e) {
            Log.w(TAG, "Notification history reached available database storage", e);
        }

        });
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
        return DatabaseRecovery.run(context, recoveryDb -> {
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
        try (Cursor cursor = recoveryDb.query("notification_history",
                notificationProjection(),
                where.length() == 0 ? null : where.toString(),
                args.isEmpty() ? null : args.toArray(new String[0]),
                null, null, "post_time DESC", limitText)) {
            while (cursor.moveToNext()) {
                result.add(readNotificationRecord(cursor));
            }
        }
        return result;

        });
    }

    public static void updateNotificationRoute(@NonNull Context context, long dbId,
                                               @Nullable String routeUri) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        if (dbId <= 0L || routeUri == null || routeUri.isEmpty()) return;
        ContentValues values = new ContentValues(1);
        values.put("route_uri", routeUri);
        recoveryDb.update("notification_history", values, "_id=?",
                new String[]{Long.toString(dbId)});
        latestNotificationsCache = null;

        });
    }

    public static void updateNotificationShortcutRoute(@NonNull Context context, long dbId,
                                                       @Nullable String shortcutId,
                                                       @Nullable String routeUri) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        if (dbId <= 0L) return;
        ContentValues values = new ContentValues(2);
        if (shortcutId != null && !shortcutId.isEmpty()) values.put("shortcut_id", shortcutId);
        if (routeUri != null && !routeUri.isEmpty()) values.put("route_uri", routeUri);
        if (values.size() == 0) return;
        recoveryDb.update("notification_history", values, "_id=?",
                new String[]{Long.toString(dbId)});
        latestNotificationsCache = null;

        });
    }

    public static void updateNotificationPendingIntentToken(@NonNull Context context, long dbId,
                                                            @Nullable String token) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        if (dbId <= 0L || token == null || token.isEmpty()) return;
        ContentValues values = new ContentValues(1);
        values.put("pending_intent_token", token);
        recoveryDb.update("notification_history", values, "_id=?",
                new String[]{Long.toString(dbId)});
        latestNotificationsCache = null;

        });
    }

    public static void clearNotificationPendingIntentToken(@NonNull Context context,
                                                           @Nullable String token) {
        DatabaseRecovery.runVoid(context, recoveryDb -> {
        if (token == null || token.isEmpty()) return;
        ContentValues values = new ContentValues(1);
        values.put("pending_intent_token", "");
        recoveryDb.update("notification_history", values, "pending_intent_token=?",
                new String[]{token});
        latestNotificationsCache = null;

        });
    }

    static String[] notificationProjection() {
        return new String[]{"_id", "notification_id", "package", "app_name", "title", "body",
                "post_time", "is_permanent", "shortcut_id", "user_serial", "route_uri",
                "pending_intent_token", "locus_id"};
    }

    private static NotificationHistoryRecord readNotificationRecord(Cursor cursor) {
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
        record.pendingIntentToken = cursor.getString(11);
        record.locusId = cursor.getString(12);
        return record;
    }
}
