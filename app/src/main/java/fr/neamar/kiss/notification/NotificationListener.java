package fr.neamar.kiss.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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
            for (StatusBarNotification sbn : timeline) {
                KissApplication.getApplication(this).getDataHandler().addToHistory(getTimelineId(sbn));
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
            KissApplication.getApplication(this).getDataHandler().addToHistory(id);
        }
    }

    private void storeNotificationDetail(SharedPreferences.Editor editor, String id, String packageKey, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if ((text == null || text.length() == 0) && n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) != null) {
            text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        }

        String titleString = title == null ? "" : title.toString();
        String textString = text == null ? "" : text.toString();
        editor.putString(id + "|package", sbn.getPackageName());
        editor.putString(id + "|key", sbn.getKey());
        editor.putString(id + "|title", titleString);
        editor.putString(id + "|text", textString);
        editor.putLong(id + "|post", sbn.getPostTime());

        // Keep the existing per-app preview used by app rows and notification dots.
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
                .remove(id + "|key")
                .remove(id + "|title")
                .remove(id + "|text")
                .remove(id + "|post")
                .apply();

        // Rebuild package preview if other notifications from the package remain.
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

        StatusBarNotification[] active = listener.getActiveNotifications();
        if (active == null) return false;
        for (StatusBarNotification sbn : active) {
            if (!key.equals(sbn.getKey())) continue;
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
        return false;
    }

    public static String getTimelineId(StatusBarNotification sbn) {
        String encoded = Base64.encodeToString(sbn.getKey().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return NOTIFICATION_SCHEME + encoded;
    }

    private String getPackageKey(StatusBarNotification sbn) {
        return sbn.getUser().hashCode() + "|" + sbn.getPackageName();
    }

    public boolean isNotificationTrivial(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final Ranking ranking = new Ranking();
            if (getCurrentRanking().getRanking(sbn.getKey(), ranking)) {
                if (!ranking.canShowBadge()) return true;
                if (ranking.getChannel() != null
                        && !ranking.getChannel().getId().equals(NotificationChannel.DEFAULT_CHANNEL_ID)) {
                    return isGroupHeader(notification);
                }
            }
        }
        return notification.priority <= Notification.PRIORITY_MIN || isOngoing(notification) || isGroupHeader(notification);
    }

    private boolean isOngoing(Notification notification) {
        return (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    private boolean isGroupHeader(Notification notification) {
        return (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }
}
