package fr.neamar.kiss.notification;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Persistent launcher-owned read state for exact Android notification events.
 *
 * Android does not expose a universal cross-app "read" bit. Smart S therefore treats a freshly
 * posted notification as unread, treats an explicit Smart S open/mark-read action as read, and
 * drops both states when Android removes the notification. Keeping a separate read set prevents a
 * process/service reconnect from making an already-opened but still-panel-visible notification
 * flash again.
 */
public final class NotificationUnreadStore {
    private static final String PREFS = "notification-read-state";
    private static final String UNREAD_IDS = "unread_ids";
    private static final String READ_IDS = "read_ids";
    private static final Object LOCK = new Object();

    private NotificationUnreadStore() {}

    public static void markUnread(@NonNull Context context, @NonNull String notificationId) {
        if (notificationId.isEmpty()) return;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            Set<String> unread = copy(prefs, UNREAD_IDS);
            Set<String> read = copy(prefs, READ_IDS);
            unread.add(notificationId);
            read.remove(notificationId);
            write(prefs, unread, read);
        }
    }

    public static void markRead(@NonNull Context context, @NonNull String notificationId) {
        if (notificationId.isEmpty()) return;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            Set<String> unread = copy(prefs, UNREAD_IDS);
            Set<String> read = copy(prefs, READ_IDS);
            unread.remove(notificationId);
            read.add(notificationId);
            write(prefs, unread, read);
        }
    }

    public static void markRemoved(@NonNull Context context, @NonNull String notificationId) {
        if (notificationId.isEmpty()) return;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            Set<String> unread = copy(prefs, UNREAD_IDS);
            Set<String> read = copy(prefs, READ_IDS);
            if (!unread.remove(notificationId) && !read.remove(notificationId)) return;
            read.remove(notificationId);
            write(prefs, unread, read);
        }
    }

    /**
     * Reconcile against Android's verified active set. Unknown active IDs are new/unread; IDs that
     * Smart S already marked read stay read across listener reconnects until Android removes them.
     */
    public static void reconcileActive(@NonNull Context context, @NonNull Set<String> activeIds) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            Set<String> unread = copy(prefs, UNREAD_IDS);
            Set<String> read = copy(prefs, READ_IDS);
            unread.retainAll(activeIds);
            read.retainAll(activeIds);
            for (String id : activeIds) {
                if (!read.contains(id) && !unread.contains(id)) unread.add(id);
            }
            write(prefs, unread, read);
        }
    }

    public static boolean isUnread(@NonNull Context context, @NonNull String notificationId) {
        if (notificationId.isEmpty()) return false;
        return prefs(context).getStringSet(UNREAD_IDS, Collections.emptySet()).contains(notificationId);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<String> copy(SharedPreferences prefs, String key) {
        return new HashSet<>(prefs.getStringSet(key, Collections.emptySet()));
    }

    private static void write(SharedPreferences prefs, Set<String> unread, Set<String> read) {
        prefs.edit().putStringSet(UNREAD_IDS, new HashSet<>(unread))
                .putStringSet(READ_IDS, new HashSet<>(read)).apply();
    }
}
