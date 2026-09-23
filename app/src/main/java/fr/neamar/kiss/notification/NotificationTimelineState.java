package fr.neamar.kiss.notification;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Lightweight persistent attention/index state for the per-notification launcher timeline.
 *
 * This intentionally lives outside NotificationListener's active-notification cache. A notification
 * can remain active after the user has opened/read it; in that case the timeline tile should stop
 * demanding attention even though Android still keeps the notification in the shade.
 */
public final class NotificationTimelineState {
    private static final String PREFS = "notification-timeline-state";
    private static final String POST_PREFIX = "post|";
    private static final String READ_PREFIX = "read|";
    private static final String HIDDEN_HISTORY_PREFIX = "hidden-history|";
    private static final String LAST_SCAN = "_last_persisted_scan";

    private NotificationTimelineState() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Records a new/reposted notification. Returns true only when this post time was not indexed
     * before. A genuinely newer post using the same Android notification key becomes unread again.
     */
    public static boolean recordIncoming(Context context, String notificationId, long postTime) {
        if (notificationId == null || notificationId.isEmpty() || postTime <= 0L) return false;
        SharedPreferences p = prefs(context);
        long previous = p.getLong(POST_PREFIX + notificationId, Long.MIN_VALUE);
        if (postTime <= previous) return false;
        p.edit()
                .putLong(POST_PREFIX + notificationId, postTime)
                .remove(READ_PREFIX + notificationId)
                .apply();
        return true;
    }

    public static void markRead(Context context, String notificationId) {
        if (notificationId == null || notificationId.isEmpty()) return;
        SharedPreferences p = prefs(context);
        long post = p.getLong(POST_PREFIX + notificationId, System.currentTimeMillis());
        p.edit().putLong(READ_PREFIX + notificationId, post).apply();
    }

    public static boolean isUnread(Context context, String notificationId) {
        if (notificationId == null
                || !notificationId.startsWith(NotificationListener.NOTIFICATION_SCHEME)
                || !NotificationListener.isNotificationActive(context, notificationId)) {
            return false;
        }
        SharedPreferences p = prefs(context);
        long post = p.getLong(POST_PREFIX + notificationId, 0L);
        if (post <= 0L) return true;
        return p.getLong(READ_PREFIX + notificationId, Long.MIN_VALUE) < post;
    }

    /** Hide only this exact notification post from launcher History; do not dismiss Android's notification. */
    public static void hideFromHistory(Context context, String notificationId, long postTime) {
        if (notificationId == null || notificationId.isEmpty() || postTime <= 0L) return;
        prefs(context).edit().putLong(HIDDEN_HISTORY_PREFIX + notificationId, postTime).apply();
    }

    /** A newer/reposted notification with the same Android key is a new History item and is not hidden. */
    public static boolean isHiddenFromHistory(Context context, String notificationId, long postTime) {
        if (notificationId == null || notificationId.isEmpty() || postTime <= 0L) return false;
        return prefs(context).getLong(HIDDEN_HISTORY_PREFIX + notificationId, Long.MIN_VALUE) == postTime;
    }

    public static long getLastPersistedScan(Context context) {
        return prefs(context).getLong(LAST_SCAN, 0L);
    }

    public static void setLastPersistedScan(Context context, long timestamp) {
        if (timestamp <= 0L) return;
        prefs(context).edit().putLong(LAST_SCAN, timestamp).apply();
    }
}
