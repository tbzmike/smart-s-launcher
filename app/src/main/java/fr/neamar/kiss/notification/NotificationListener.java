package fr.neamar.kiss.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;

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
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.ui.CompactNotificationFrame;
import fr.neamar.kiss.utils.AppLaunchUtils;
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

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getBaseContext().getSharedPreferences(NOTIFICATION_PREFERENCES_NAME, Context.MODE_PRIVATE);
        details = getBaseContext().getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public void onListenerConnected() {
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
            if (seedTimeline) persistHistory(sbn, getTimelineId(sbn));
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
                if (seeded.add(groupKey)) KissApplication.getApplication(this).getDataHandler().addToHistory(getGroupId(groupKey));
            }
        }
    }

    @Override public void onListenerDisconnected() {
        prefs.edit().clear().apply();
        details.edit().clear().apply();
        super.onListenerDisconnected();
    }

    @Override public void onNotificationRankingUpdate(RankingMap rankingMap) {
        super.onNotificationRankingUpdate(rankingMap);
        refreshAllNotifications(false);
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        String id = getTimelineId(sbn);
        persistHistory(sbn, id);
        if (isNotificationTrivial(sbn)) return;

        String packageKey = getPackageKey(sbn);
        Set<String> currentNotifications = getCurrentNotificationsForPackage(packageKey);
        currentNotifications.add(Integer.toString(sbn.getId()));
        prefs.edit().putStringSet(packageKey, currentNotifications).apply();

        Set<String> active = new HashSet<>(details.getStringSet(ACTIVE_NOTIFICATION_IDS, Collections.emptySet()));
        active.add(id);
        SharedPreferences.Editor detailEditor = details.edit();
        detailEditor.putStringSet(ACTIVE_NOTIFICATION_IDS, active);
        storeNotificationDetail(detailEditor, id, packageKey, sbn);
        detailEditor.apply();

        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("enable-notification-history", false)) {
            KissApplication.getApplication(this).getDataHandler().addToHistory(getGroupId(packageKey));
        }
        sendBroadcast(new Intent(MainActivity.LOAD_OVER));
    }

    private void persistHistory(StatusBarNotification sbn, String id) {
        Notification n = sbn.getNotification();
        if (n == null) return;
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null || text.length() == 0) text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        SmartStateStore.saveNotification(this, id, sbn.getPackageName(), getAppName(sbn.getPackageName()),
                title == null ? "" : title.toString(), text == null ? "" : text.toString(), sbn.getPostTime(),
                isPermanentForHistory(sbn));
    }

    private String getAppName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            CharSequence label = info.loadLabel(pm);
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
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

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        removeCachedNotification(sbn);
    }

    private void removeCachedNotification(StatusBarNotification sbn) {
        String packageKey = getPackageKey(sbn);
        Set<String> currentNotifications = getCurrentNotificationsForPackage(packageKey);
        currentNotifications.remove(Integer.toString(sbn.getId()));
        if (currentNotifications.isEmpty()) prefs.edit().remove(packageKey).apply();
        else prefs.edit().putStringSet(packageKey, currentNotifications).apply();

        String id = getTimelineId(sbn);
        Set<String> active = new HashSet<>(details.getStringSet(ACTIVE_NOTIFICATION_IDS, Collections.emptySet()));
        active.remove(id);
        SharedPreferences.Editor edit = details.edit()
                .putStringSet(ACTIVE_NOTIFICATION_IDS, active)
                .remove(id + "|package").remove(id + "|group").remove(id + "|key")
                .remove(id + "|title").remove(id + "|text").remove(id + "|post");

        if (currentNotifications.isEmpty()) {
            edit.remove(packageKey + "|text").remove(packageKey + "|key");
            DBHelper.removeFromHistory(this, getGroupId(packageKey));
        } else {
            String latestId = null;
            long latestTime = Long.MIN_VALUE;
            for (String activeId : active) {
                if (!packageKey.equals(details.getString(activeId + "|group", ""))) continue;
                long time = details.getLong(activeId + "|post", 0L);
                if (time > latestTime) { latestTime = time; latestId = activeId; }
            }
            if (latestId != null) {
                String title = details.getString(latestId + "|title", "");
                String body = details.getString(latestId + "|text", "");
                String message = title == null ? "" : title;
                if (body != null && !body.isEmpty()) message = message.isEmpty() ? body : message + ": " + body;
                edit.putString(packageKey + "|text", message);
                edit.putString(packageKey + "|key", details.getString(latestId + "|key", ""));
            }
        }
        edit.apply();
        sendBroadcast(new Intent(MainActivity.LOAD_OVER));
    }

    public Set<String> getCurrentNotificationsForPackage(String packageKey) {
        Set<String> currentNotifications = prefs.getStringSet(packageKey, null);
        return currentNotifications == null ? new HashSet<>() : new HashSet<>(currentNotifications);
    }

    public static String getLatestMessage(Context context, String packageKey) {
        return context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(packageKey + "|text", "");
    }

    public static String getNotificationPackage(Context context, String notificationId) {
        return context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(notificationId + "|package", null);
    }

    public static boolean isNotificationActive(Context context, String notificationId) {
        Set<String> active = context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getStringSet(ACTIVE_NOTIFICATION_IDS, Collections.emptySet());
        return active != null && active.contains(notificationId);
    }

    public static String getExpandedNotificationText(Context context, String notificationId) {
        StatusBarNotification sbn = findActiveNotification(context, notificationId);
        if (sbn == null || sbn.getNotification() == null) {
            return context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .getString(notificationId + "|text", "");
        }
        return extractExpandedText(sbn.getNotification());
    }

    private static String extractExpandedText(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return "";
        StringBuilder text = new StringBuilder();

        CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        appendDistinct(text, bigText);

        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            for (CharSequence line : lines) appendDistinct(text, line);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Parcelable[] messageBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (messageBundles != null) {
                List<Notification.MessagingStyle.Message> messages =
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(messageBundles);
                if (messages != null) {
                    for (Notification.MessagingStyle.Message message : messages) {
                        if (message != null) appendDistinct(text, message.getText());
                    }
                }
            }
        }

        CharSequence normalText = extras.getCharSequence(Notification.EXTRA_TEXT);
        appendDistinct(text, normalText);
        return text.toString();
    }

    private static void appendDistinct(StringBuilder builder, CharSequence value) {
        if (value == null) return;
        String clean = value.toString().trim();
        if (clean.isEmpty()) return;
        String current = builder.toString();
        if (!current.isEmpty() && (current.equals(clean) || current.contains(clean))) return;
        if (builder.length() > 0) builder.append('\n');
        builder.append(clean);
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
                String actionTitle = action.title == null ? "" : action.title.toString().toLowerCase(Locale.ROOT);
                if (!actionTitle.contains("read")) continue;
                try {
                    if (action.actionIntent != null) action.actionIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "Mark-as-read action was canceled", e);
                }
                break;
            }
        }

        listener.removeCachedNotification(sbn);
        try {
            listener.cancelNotification(key);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to dismiss notification after mark read", e);
            listener.refreshAllNotifications(false);
            return false;
        }
    }

    public static boolean hasMarkAllReadAction(Context context, String groupKey) {
        NotificationListener listener = instance;
        if (listener == null) return false;
        StatusBarNotification[] active = listener.getActiveNotifications();
        if (active == null) return false;
        for (StatusBarNotification sbn : active) {
            if (!groupKey.equals(listener.getPackageKey(sbn))) continue;
            if (findMarkAllReadAction(sbn.getNotification()) != null) return true;
        }
        return false;
    }

    public static boolean markAllRead(Context context, String groupKey) {
        NotificationListener listener = instance;
        if (listener == null) return false;
        StatusBarNotification[] active = listener.getActiveNotifications();
        if (active == null) return false;

        Notification.Action markAllAction = null;
        for (StatusBarNotification sbn : active) {
            if (!groupKey.equals(listener.getPackageKey(sbn))) continue;
            markAllAction = findMarkAllReadAction(sbn.getNotification());
            if (markAllAction != null) break;
        }
        if (markAllAction == null || markAllAction.actionIntent == null) return false;

        try {
            markAllAction.actionIntent.send();
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            Log.w(TAG, "Mark-all-as-read action failed", e);
            return false;
        }

        List<NotificationSnapshot> snapshots = getGroupNotifications(context, groupKey);
        boolean removed = false;
        for (NotificationSnapshot snapshot : snapshots) {
            removed |= dismissNotification(context, snapshot.id);
        }
        return removed || snapshots.isEmpty();
    }

    public static boolean markGroupRead(Context context, String groupKey) {
        if (hasMarkAllReadAction(context, groupKey) && markAllRead(context, groupKey)) return true;
        List<NotificationSnapshot> snapshots = getGroupNotifications(context, groupKey);
        if (snapshots.isEmpty()) return false;
        boolean success = false;
        for (NotificationSnapshot snapshot : snapshots) success |= markNotificationRead(context, snapshot.id);
        return success;
    }

    private static Notification.Action findMarkAllReadAction(Notification notification) {
        if (notification == null || notification.actions == null) return null;
        for (Notification.Action action : notification.actions) {
            if (action == null || action.actionIntent == null || action.title == null) continue;
            String title = action.title.toString().trim().toLowerCase(Locale.ROOT);
            if (title.contains("mark all") && title.contains("read")) return action;
            if (title.contains("read all") || title.equals("all read")) return action;
        }
        return null;
    }

    private static boolean cancelByKey(String key) {
        NotificationListener listener = instance;
        if (listener == null || key == null) return false;
        StatusBarNotification sbn = listener.findActiveByKey(key);
        if (sbn == null) return false;
        listener.removeCachedNotification(sbn);
        try {
            listener.cancelNotification(key);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to dismiss notification", e);
            listener.refreshAllNotifications(false);
            return false;
        }
    }

    public static boolean openNotification(Context context, String notificationId) {
        SharedPreferences details = context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);
        String key = details.getString(notificationId + "|key", null);
        String packageName = details.getString(notificationId + "|package", null);
        NotificationListener listener = instance;
        if (listener == null || key == null) return false;
        if (packageName != null && !AppLaunchUtils.ensurePackageEnabled(context, packageName)) return false;

        StatusBarNotification sbn = listener.findActiveByKey(key);
        if (sbn == null || sbn.getNotification() == null) return false;
        PendingIntent contentIntent = sbn.getNotification().contentIntent;
        if (contentIntent == null) return false;
        try {
            // Preserve the app's exact deep link/conversation target. Supplying a synthetic
            // fill-in Intent here can change or invalidate routing for immutable PendingIntents.
            contentIntent.send();
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            Log.w(TAG, "Notification content intent could not be opened", e);
            return false;
        }
    }

    public static boolean hasReplyAction(Context context, String notificationId) {
        StatusBarNotification sbn = findActiveNotification(context, notificationId);
        return sbn != null && findReplyAction(sbn.getNotification()) != null;
    }

    public static boolean replyToNotification(Context context, String notificationId, String replyText) {
        if (replyText == null || replyText.trim().isEmpty()) return false;
        StatusBarNotification sbn = findActiveNotification(context, notificationId);
        if (sbn == null) return false;

        String packageName = sbn.getPackageName();
        if (!AppLaunchUtils.ensurePackageEnabled(context, packageName)) return false;

        Notification.Action action = findReplyAction(sbn.getNotification());
        if (action == null || action.actionIntent == null) return false;
        RemoteInput[] remoteInputs = action.getRemoteInputs();
        if (remoteInputs == null || remoteInputs.length == 0) return false;

        Bundle results = new Bundle();
        boolean hasFreeFormInput = false;
        for (RemoteInput remoteInput : remoteInputs) {
            if (!remoteInput.getAllowFreeFormInput()) continue;
            results.putCharSequence(remoteInput.getResultKey(), replyText);
            hasFreeFormInput = true;
        }
        if (!hasFreeFormInput) return false;

        Intent fillInIntent = new Intent();
        RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, results);
        try {
            action.actionIntent.send(context, 0, fillInIntent);
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            Log.w(TAG, "Inline reply action failed", e);
            return false;
        }
    }

    private static StatusBarNotification findActiveNotification(Context context, String notificationId) {
        String key = context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(notificationId + "|key", null);
        NotificationListener listener = instance;
        if (listener == null || key == null) return null;
        return listener.findActiveByKey(key);
    }

    private static Notification.Action findReplyAction(Notification notification) {
        if (notification == null || notification.actions == null) return null;
        for (Notification.Action action : notification.actions) {
            if (action == null || action.actionIntent == null) continue;
            RemoteInput[] remoteInputs = action.getRemoteInputs();
            if (remoteInputs == null) continue;
            for (RemoteInput remoteInput : remoteInputs) {
                if (remoteInput != null && remoteInput.getAllowFreeFormInput()) return action;
            }
        }
        return null;
    }

    public static View createNativeGroupView(Context context, String groupKey, ViewGroup parent, boolean expanded) {
        NotificationListener listener = instance;
        if (listener == null) return null;
        StatusBarNotification[] active = listener.getActiveNotifications();
        if (active == null) return null;
        StatusBarNotification latest = null;
        for (StatusBarNotification sbn : active) {
            if (listener.isNotificationTrivial(sbn)) continue;
            if (!groupKey.equals(listener.getPackageKey(sbn))) continue;
            if (latest == null || sbn.getPostTime() > latest.getPostTime()) latest = sbn;
        }
        return latest == null ? null : createNativeView(context, latest.getNotification(), parent, expanded);
    }

    public static View createNativeNotificationView(Context context, String notificationId, ViewGroup parent, boolean expanded) {
        String key = context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(notificationId + "|key", null);
        NotificationListener listener = instance;
        if (listener == null || key == null) return null;
        StatusBarNotification sbn = listener.findActiveByKey(key);
        return sbn == null ? null : createNativeView(context, sbn.getNotification(), parent, expanded);
    }

    private static View createNativeView(Context context, Notification notification, ViewGroup parent, boolean expanded) {
        if (notification == null) return null;
        RemoteViews remoteViews = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Notification.Builder builder = Notification.Builder.recoverBuilder(context, notification);
                remoteViews = expanded ? builder.createBigContentView() : builder.createContentView();
            }
            if (remoteViews == null) remoteViews = expanded && notification.bigContentView != null ? notification.bigContentView : notification.contentView;
            if (remoteViews == null && expanded) remoteViews = notification.contentView;
            if (remoteViews == null) return null;

            CompactNotificationFrame wrapper = new CompactNotificationFrame(context);
            float density = context.getResources().getDisplayMetrics().density;
            int screenHeightDp = Math.max(1,
                    Math.round(context.getResources().getDisplayMetrics().heightPixels / density));
            wrapper.setMaxHeightDp(expanded ? Math.max(320, screenHeightDp * 70 / 100) : 88);
            wrapper.setClipChildren(!expanded);
            wrapper.setClipToPadding(!expanded);
            View nativeView = remoteViews.apply(context, wrapper);
            wrapper.addView(nativeView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return wrapper;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to inflate native notification RemoteViews", e);
            return null;
        }
    }

    private StatusBarNotification findActiveByKey(String key) {
        StatusBarNotification[] active = getActiveNotifications();
        if (active == null) return null;
        for (StatusBarNotification sbn : active) if (key.equals(sbn.getKey())) return sbn;
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
            result.add(new NotificationSnapshot(id, title == null ? "" : title, text == null ? "" : text,
                    details.getLong(id + "|post", 0L)));
        }
        result.sort(Comparator.comparingLong((NotificationSnapshot n) -> n.postTime).reversed());
        return result;
    }

    public static String getTimelineId(StatusBarNotification sbn) {
        String encoded = Base64.encodeToString(sbn.getKey().getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return NOTIFICATION_SCHEME + encoded;
    }

    public static String getGroupId(String groupKey) {
        String encoded = Base64.encodeToString(groupKey.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return NOTIFICATION_GROUP_SCHEME + encoded;
    }

    private String getPackageKey(StatusBarNotification sbn) {
        return sbn.getUser().hashCode() + "|" + sbn.getPackageName();
    }

    private boolean isPermanentForHistory(StatusBarNotification sbn) {
        if (sbn == null) return false;
        Notification notification = sbn.getNotification();
        if (notification == null) return false;
        return !sbn.isClearable() || isOngoing(notification) || isForegroundService(notification);
    }

    public boolean isNotificationTrivial(StatusBarNotification sbn) {
        if (sbn == null || !sbn.isClearable()) return true;
        Notification notification = sbn.getNotification();
        if (notification == null) return true;
        if (isOngoing(notification) || isForegroundService(notification) || isGroupHeader(notification)) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final Ranking ranking = new Ranking();
            if (getCurrentRanking().getRanking(sbn.getKey(), ranking)) {
                if (ranking.getChannel() != null && !ranking.getChannel().getId().equals(NotificationChannel.DEFAULT_CHANNEL_ID)
                        && isGroupHeader(notification)) return true;
            }
        }
        return notification.priority <= Notification.PRIORITY_MIN;
    }

    private boolean isOngoing(Notification notification) { return (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0; }
    private boolean isForegroundService(Notification notification) {
        return (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0 || Notification.CATEGORY_SERVICE.equals(notification.category);
    }
    private boolean isGroupHeader(Notification notification) { return (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0; }
}
