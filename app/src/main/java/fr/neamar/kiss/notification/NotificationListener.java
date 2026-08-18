package fr.neamar.kiss.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.preference.PreferenceManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.UserHandle;

public class NotificationListener extends NotificationListenerService {
    public static final String TAG = NotificationListener.class.getSimpleName();
    public static final String NOTIFICATION_PREFERENCES_NAME = "notifications";
    private static final String DETAIL_PREFERENCES_NAME = "notification-details";
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
        refreshAllNotifications();
    }

    private void refreshAllNotifications() {
        StatusBarNotification[] sbns = getActiveNotifications();
        Map<String, Set<String>> notificationsByPackage = new HashMap<>();
        SharedPreferences.Editor detailEditor = details.edit().clear();
        for (StatusBarNotification sbn : sbns) {
            if (isNotificationTrivial(sbn)) continue;
            String packageKey = getPackageKey(sbn);
            notificationsByPackage.computeIfAbsent(packageKey, k -> new HashSet<>()).add(Integer.toString(sbn.getId()));
            storeNotificationDetail(detailEditor, packageKey, sbn);
        }
        detailEditor.apply();

        SharedPreferences.Editor editor = prefs.edit();
        Set<String> allKeys = new HashSet<>(prefs.getAll().keySet());
        allKeys.addAll(notificationsByPackage.keySet());
        for (String packageKey : allKeys) {
            if (notificationsByPackage.containsKey(packageKey)) editor.putStringSet(packageKey, notificationsByPackage.get(packageKey));
            else editor.remove(packageKey);
        }
        editor.apply();
    }

    @Override
    public void onListenerDisconnected() {
        prefs.edit().clear().apply();
        details.edit().clear().apply();
        super.onListenerDisconnected();
    }

    @Override public void onNotificationRankingUpdate(RankingMap rankingMap) { super.onNotificationRankingUpdate(rankingMap); refreshAllNotifications(); }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (isNotificationTrivial(sbn)) return;
        String packageKey = getPackageKey(sbn);
        Set<String> currentNotifications = getCurrentNotificationsForPackage(packageKey);
        currentNotifications.add(Integer.toString(sbn.getId()));
        prefs.edit().putStringSet(packageKey, currentNotifications).apply();
        SharedPreferences.Editor detailEditor = details.edit();
        storeNotificationDetail(detailEditor, packageKey, sbn);
        detailEditor.apply();
        addNotificationToHistory(sbn);
    }

    private void storeNotificationDetail(SharedPreferences.Editor editor, String packageKey, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        String message = "";
        if (title != null && title.length() > 0) message = title.toString();
        if (text != null && text.length() > 0) message = message.isEmpty() ? text.toString() : message + ": " + text;
        editor.putString(packageKey + "|text", message);
        editor.putString(packageKey + "|key", sbn.getKey());
    }

    private void addNotificationToHistory(StatusBarNotification sbn) {
        Context context = getBaseContext();
        if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean("enable-notification-history", false)) {
            KissApplication.getApplication(context).getDataHandler().addPackageToHistory(context, new UserHandle(context, sbn.getUser()), sbn.getPackageName());
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String packageKey = getPackageKey(sbn);
        Set<String> currentNotifications = getCurrentNotificationsForPackage(packageKey);
        currentNotifications.remove(Integer.toString(sbn.getId()));
        if (currentNotifications.isEmpty()) {
            prefs.edit().remove(packageKey).apply();
            details.edit().remove(packageKey + "|text").remove(packageKey + "|key").apply();
        } else {
            prefs.edit().putStringSet(packageKey, currentNotifications).apply();
            refreshAllNotifications();
        }
    }

    private String getPackageKey(StatusBarNotification sbn) { return sbn.getUser().hashCode() + "|" + sbn.getPackageName(); }

    public Set<String> getCurrentNotificationsForPackage(String packageName) {
        Set<String> currentNotifications = prefs.getStringSet(packageName, null);
        return currentNotifications == null ? new HashSet<>() : new HashSet<>(currentNotifications);
    }

    public static String getLatestMessage(Context context, String packageKey) {
        return context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(packageKey + "|text", "");
    }

    public static boolean dismissLatest(Context context, String packageKey) {
        String key = context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(packageKey + "|key", null);
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

    public boolean isNotificationTrivial(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final Ranking ranking = new Ranking();
            getCurrentRanking().getRanking(sbn.getKey(), ranking);
            if (!ranking.canShowBadge()) return true;
            if (ranking.getChannel() != null && !ranking.getChannel().getId().equals(NotificationChannel.DEFAULT_CHANNEL_ID)) return isGroupHeader(notification);
        }
        return notification.priority <= Notification.PRIORITY_MIN || isOngoing(notification) || isGroupHeader(notification);
    }

    private boolean isOngoing(Notification notification) { return (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0; }
    private boolean isGroupHeader(Notification notification) { return (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0; }
}
