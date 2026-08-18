package fr.neamar.kiss.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;

import androidx.preference.PreferenceManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.utils.Log;

public class NotificationListener extends NotificationListenerService {
    public static final String TAG = NotificationListener.class.getSimpleName();
    public static final String NOTIFICATION_PREFERENCES_NAME = "notifications";
    public static final String DETAIL_PREFERENCES_NAME = "notification-details";
    public static final String ACTIVE_NOTIFICATION_IDS = "_active_notification_ids";
    public static final String NOTIFICATION_SCHEME = "notification://";
    public static final String NOTIFICATION_GROUP_SCHEME = "notification-group://";

    public static final class NotificationSnapshot {
        public final String id;
        public final String title;
        public final String text;
        public final long postTime;

        NotificationSnapshot(String id, String title, String text, long postTime) {
            this.id = id;
            this.title = title;
            this.text = text;
            this.postTime = postTime;
        }
    }

    private static volatile NotificationListener instance;
    private SharedPreferences prefs;
    private SharedPreferences details;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getBaseContext().getSharedPreferences(NOTIFICATION_PREFERENCES_NAME, Context.MODE_PRIVATE);
        details = getBaseContext().getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        refreshAllNotifications(true);
    }

    private void refreshAllNotifications(boolean seedTimeline) {
        StatusBarNotification[] sbns = getActiveNotifications();
        if (sbns == null) sbns = new StatusBarNotification[0];

        Map<String, Set<String>> notificationsByPackage = new HashMap<>();
        Set<String> activeIds = new HashSet<>();
        List<StatusBarNotification> timeline = new ArrayList<>();
        SharedPreferences.Editor detailEditor = details.edit().clear();

        for (StatusBarNotification sbn : sbns) {
            if (isNotificationTrivial(sbn)) continue;
            String packageKey = getPackageKey(sbn);
            notificationsByPackage.computeIfAbsent(packageKey, k -> new HashSet<>()).add(Integer.toString(sbn.getId()));
            String id = getTimelineId(sbn);
            activeIds.add(id);
            storeNotificationDetail(detailEditor, id, packageKey, sbn);
            timeline.add(sbn);
        }
        detailEditor.putStringSet(ACTIVE_NOTIFICATION_IDS, activeIds).apply();

        SharedPreferences.Editor editor = prefs.edit();
        Set<String> allKeys = new HashSet<>(prefs.getAll().keySet());
        allKeys.addAll(notificationsByPackage.keySet());
        for (String packageKey : allKeys) {
            if (notificationsByPackage.containsKey(packageKey)) editor.putStringSet(packageKey, notificationsByPackage.get(packageKey));
            else editor.remove(packageKey);
        }
        editor.apply();

        if (seedTimeline && PreferenceManager.getDefaultSharedPreferences(this).getBoolean("enable-notification-history", false)) {
            timeline.sort(Comparator.comparingLong(StatusBarNotification::getPostTime));
            Set<String> seeded = new HashSet<>();
            for (StatusBarNotification sbn : timeline) {
                String groupKey = getPackageKey(sbn);
                if (seeded.add(groupKey)) {
                    KissApplication.getApplication(this).getDataHandler().addToHistory(getGroupId(groupKey));
                }
            }
        }
    }

    @Override
    public void onListenerDisconnected() {
        prefs.edit().clear().apply();
        details.edit().clear().apply();
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        super.onNotificationRankingUpdate(rankingMap);
        refreshAllNotifications(false);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || isNotificationTrivial(sbn)) return;

        String packageKey = getPackageKey(sbn);
        Set<String> currentNotifications = getCurrentNotificationsForPackage(packageKey);
        currentNotifications.add(Integer.toString(sbn.getId()));
        prefs.edit().putStringSet(packageKey, currentNotifications).apply();

        String id = getTimelineId(sbn);
        Set<String> active = new HashSet<>(details.getStringSet(ACTIVE_NOTIFICATION_IDS, Collections.emptySet()));
        active.add(id);
        SharedPreferences.Editor detailEditor = details.edit();
        detailEditor.putStringSet(ACTIVE_NOTIFICATION_IDS, active);
        storeNotificationDetail(detailEditor, id, packageKey, sbn);
        detailEditor.apply();

        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("enable-notification-history", false)) {
            KissApplication.getApplication(this).getDataHandler().addToHistory(getGroupId(packageKey));
        }
    }

    private void storeNotificationDetail(SharedPreferences.Editor editor, String id, String packageKey, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null || text.length() == 0) text = n.extras.getCharSequence(Notification.EXTRA_TEXT);

        String titleString = title == null ? "" : title.toString();
        String textString = text == null ? "" : text.toString();
        editor.putString(id + "|package", sbn.getPackageName());
        editor.putString(id + "|group", packageKey);
        editor.putString(id + "|key", sbn.getKey());
        editor.putString(id + "|title", titleString);
        editor.putString(id + "|text", textString);
        editor.putLong(id + "|post", sbn.getPostTime());

        String message = titleString;
        if (!textString.isEmpty()) message = message.isEmpty() ? textString : message + ": " + textString;
        editor.putString(packageKey + "|text", message);
        editor.putString(packageKey + "|key", sbn.getKey());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;

        String packageKey = getPackageKey(sbn);
        Set<String> currentNotifications = getCurrentNotificationsForPackage(packageKey);
        currentNotifications.remove(Integer.toString(sbn.getId()));
        if (currentNotifications.isEmpty()) prefs.edit().remove(packageKey).apply();
        else prefs.edit().putStringSet(packageKey, currentNotifications).apply();

        String id = getTimelineId(sbn);
        Set<String> active = new HashSet<>(details.getStringSet(ACTIVE_NOTIFICATION_IDS, Collections.emptySet()));
        active.remove(id);
        details.edit()
                .putStringSet(ACTIVE_NOTIFICATION_IDS, active)
                .remove(id + "|package")
                .remove(id + "|group")
                .remove(id + "|key")
                .remove(id + "|title")
                .remove(id + "|text")
                .remove(id + "|post")
                .apply();

        if (!currentNotifications.isEmpty()) refreshAllNotifications(false);
        else details.edit().remove(packageKey + "|text").remove(packageKey + "|key").apply();
    }

    public Set<String> getCurrentNotificationsForPackage(String packageKey) {
        Set<String> currentNotifications = prefs.getStringSet(packageKey, null);
        return currentNotifications == null ? new HashSet<>() : new HashSet<>(currentNotifications);
    }

    public static String getLatestMessage(Context context, String packageKey) {
        return context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(packageKey + "|text", "");
    }

    public static boolean dismissLatest(Context context, String packageKey) {
        String key = context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(packageKey + "|key", null);
        return cancelByKey(key);
    }

    public static boolean dismissNotification(Context context, String notificationId) {
        String key = context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(notificationId + "|key", null);
        return cancelByKey(key);
    }

    public static boolean markNotificationRead(Context context, String notificationId) {
        String key = context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(notificationId + "|key", null);
        NotificationListener listener = instance;
        if (listener == null || key == null) return false;
        StatusBarNotification sbn = listener.findActiveByKey(key);
        if (sbn == null) return false;

        Notification.Action[] actions = sbn.getNotification().actions;
        if (actions != null) {
            for (Notification.Action action : actions) {
                CharSequence title = action.title;
                String actionTitle = title == null ? "" : title.toString().toLowerCase(Locale.ROOT);
                if (!actionTitle.contains("read")) continue;
                try {
                    if (action.actionIntent != null) action.actionIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "Mark-as-read action was canceled", e);
                }
                break;
            }
        }

        try {
            listener.cancelNotification(key);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to dismiss notification after mark read", e);
            return false;
        }
    }

    public static boolean markGroupRead(Context context, String groupKey) {
        List<NotificationSnapshot> snapshots = getGroupNotifications(context, groupKey);
        if (snapshots.isEmpty()) return false;
        boolean success = false;
        for (NotificationSnapshot snapshot : snapshots) {
            success |= markNotificationRead(context, snapshot.id);
        }
        return success;
    }

    private static boolean cancelByKey(String key) {
        NotificationListener listener = instance;
        if (listener == null || key == null) return false;
        try {
            listener.cancelNotification(key);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to dismiss notification", e);
            return false;
        }
    }

    public static boolean openNotification(Context context, String notificationId) {
        String key = context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(notificationId + "|key", null);
        NotificationListener listener = instance;
        if (listener == null || key == null) return false;

        StatusBarNotification sbn = listener.findActiveByKey(key);
        if (sbn == null) return false;
        PendingIntent contentIntent = sbn.getNotification().contentIntent;
        if (contentIntent == null) return false;
        try {
            contentIntent.send();
            return true;
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, "Notification content intent was canceled", e);
            return false;
        }
    }

    private StatusBarNotification findActiveByKey(String key) {
        StatusBarNotification[] active = getActiveNotifications();
        if (active == null) return null;
        for (StatusBarNotification sbn : active) {
            if (key.equals(sbn.getKey())) return sbn;
        }
        return null;
    }

    public static List<NotificationSnapshot> getGroupNotifications(Context context, String groupKey) {
        SharedPreferences details = context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);
        Set<String> active = details.getStringSet(ACTIVE_NOTIFICATION_IDS, Collections.emptySet());
        if (active == null || active.isEmpty()) return Collections.emptyList();

        List<NotificationSnapshot> result = new ArrayList<>();
        for (String id : new HashSet<>(active)) {
            if (!groupKey.equals(details.getString(id + "|group", ""))) continue;
            String title = details.getString(id + "|title", "");
            String text = details.getString(id + "|text", "");
            long post = details.getLong(id + "|post", 0L);
            result.add(new NotificationSnapshot(id,
                    title == null ? "" : title,
                    text == null ? "" : text,
                    post));
        }
        result.sort(Comparator.comparingLong((NotificationSnapshot n) -> n.postTime).reversed());
        return result;
    }

    public static String getTimelineId(StatusBarNotification sbn) {
        String encoded = Base64.encodeToString(sbn.getKey().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return NOTIFICATION_SCHEME + encoded;
    }

    public static String getGroupId(String groupKey) {
        String encoded = Base64.encodeToString(groupKey.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return NOTIFICATION_GROUP_SCHEME + encoded;
    }

    private String getPackageKey(StatusBarNotification sbn) {
        return sbn.getUser().hashCode() + "|" + sbn.getPackageName();
    }

    public boolean isNotificationTrivial(StatusBarNotification sbn) {
        if (sbn == null || !sbn.isClearable()) return true;
        Notification notification = sbn.getNotification();
        if (notification == null) return true;
        if (isOngoing(notification) || isForegroundService(notification) || isGroupHeader(notification)) return true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final Ranking ranking = new Ranking();
            if (getCurrentRanking().getRanking(sbn.getKey(), ranking)) {
                if (ranking.getChannel() != null
                        && !ranking.getChannel().getId().equals(NotificationChannel.DEFAULT_CHANNEL_ID)
                        && isGroupHeader(notification)) return true;
            }
        }
        return notification.priority <= Notification.PRIORITY_MIN;
    }

    private boolean isOngoing(Notification notification) {
        return (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    private boolean isForegroundService(Notification notification) {
        return (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0
                || Notification.CATEGORY_SERVICE.equals(notification.category);
    }

    private boolean isGroupHeader(Notification notification) {
        return (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }
}
