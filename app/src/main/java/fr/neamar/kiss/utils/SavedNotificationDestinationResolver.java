package fr.neamar.kiss.utils;

import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.UserManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.notification.NotificationListener;

/**
 * Opens the exact destination represented by a saved notification when Android exposes a stable
 * route for it. App-published conversation shortcuts are preferred because they represent the
 * durable exact conversation/action; the posting app's original PendingIntent is the second exact
 * route. Notification title/body text is deliberately never guessed into a private deep link.
 */
public final class SavedNotificationDestinationResolver {
    private static final String TAG = SavedNotificationDestinationResolver.class.getSimpleName();

    private SavedNotificationDestinationResolver() {}

    public static boolean hasExactTarget(@NonNull Context context,
                                         @Nullable NotificationHistoryRecord record) {
        if (record == null) return false;
        if (!TextUtils.isEmpty(record.notificationId)
                && (NotificationListener.isNotificationActive(context, record.notificationId)
                || NotificationListener.hasRetainedContentIntent(record.notificationId))) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !TextUtils.isEmpty(record.shortcutId);
    }

    /**
     * Open only an exact notification destination. This method never falls back to an app's main
     * launcher activity, so callers can tell the user when exact routing is unavailable.
     */
    public static boolean openExact(@NonNull Context context,
                                    @Nullable NotificationHistoryRecord record) {
        if (record == null) return false;

        if (openPublishedShortcut(context, record)) return true;

        return !TextUtils.isEmpty(record.notificationId)
                && NotificationListener.openNotification(context, record.notificationId);
    }

    /**
     * Try only the durable app-published conversation shortcut represented by a saved notification.
     * No package/main-activity fallback is allowed here.
     */
    public static boolean openPublishedShortcut(@NonNull Context context,
                                                @Nullable NotificationHistoryRecord record) {
        if (record == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || TextUtils.isEmpty(record.packageName)
                || TextUtils.isEmpty(record.shortcutId)) {
            return false;
        }
        return openConversationShortcut(context, record);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private static boolean openConversationShortcut(@NonNull Context context,
                                                     @NonNull NotificationHistoryRecord record) {
        LauncherApps launcherApps = ContextCompat.getSystemService(context, LauncherApps.class);
        UserManager userManager = ContextCompat.getSystemService(context, UserManager.class);
        if (launcherApps == null || userManager == null || !launcherApps.hasShortcutHostPermission()) {
            return false;
        }

        List<android.os.UserHandle> profiles = userManager.getUserProfiles();
        if (profiles == null || profiles.isEmpty()) return false;

        // First honor the profile that actually posted the notification on this device.
        if (record.userSerial >= 0L) {
            ShortcutInfo preferred = null;
            for (android.os.UserHandle profile : profiles) {
                if (userManager.getSerialNumberForUser(profile) != record.userSerial) continue;
                preferred = findShortcut(context, userManager, profile,
                        record.packageName, record.shortcutId);
                if (preferred != null) break;
            }
            if (preferred != null) return launch(launcherApps, preferred);
        }

        // A restored backup can land on a phone whose Android profile serials differ. Fall back
        // only when exactly one accessible profile publishes the saved shortcut; never guess when
        // the same route exists in multiple profiles.
        List<ShortcutInfo> matches = new ArrayList<>(2);
        for (android.os.UserHandle profile : profiles) {
            ShortcutInfo shortcut = findShortcut(context, userManager, profile,
                    record.packageName, record.shortcutId);
            if (shortcut != null) matches.add(shortcut);
            if (matches.size() > 1) return false;
        }
        return matches.size() == 1 && launch(launcherApps, matches.get(0));
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Nullable
    private static ShortcutInfo findShortcut(@NonNull Context context,
                                             @NonNull UserManager userManager,
                                             @NonNull android.os.UserHandle profile,
                                             @NonNull String packageName,
                                             @NonNull String shortcutId) {
        try {
            if (!userManager.isUserRunning(profile) || !userManager.isUserUnlocked(profile)) return null;
            return ShortcutUtil.getShortCut(context, profile, packageName, shortcutId);
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "Unable to resolve saved notification shortcut", e);
            return null;
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private static boolean launch(@NonNull LauncherApps launcherApps,
                                  @NonNull ShortcutInfo shortcut) {
        try {
            launcherApps.startShortcut(shortcut, null, null);
            return true;
        } catch (IllegalStateException | SecurityException | android.content.ActivityNotFoundException e) {
            Log.w(TAG, "Unable to open exact saved notification shortcut", e);
            return false;
        }
    }
}
