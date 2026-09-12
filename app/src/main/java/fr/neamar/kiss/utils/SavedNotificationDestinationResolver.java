package fr.neamar.kiss.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Base64;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.notification.NotificationPendingIntentStore;

/**
 * Opens the exact destination represented by a saved notification when Android exposes a stable
 * route for it. The posting app's original PendingIntent is authoritative; app-published
 * conversation shortcuts and their verified intent URI provide durable fallbacks. Notification
 * title/body text is deliberately never guessed into a private deep link.
 */
public final class SavedNotificationDestinationResolver {
    private static final String TAG = SavedNotificationDestinationResolver.class.getSimpleName();
    private static final long ENABLE_SETTLE_DELAY_MS = 500L;
    private static final long EXACT_RETRY_DELAY_MS = 400L;
    private static final int EXACT_RETRY_COUNT = 3;
    private static final long LISTENER_RETRY_DELAY_MS = 250L;
    private static final int LISTENER_RETRY_COUNT = 40;
    private static final String FAIREMAIL_PACKAGE = "eu.faircode.email";
    private static final Pattern FAIREMAIL_UNSEEN_TAG =
            Pattern.compile("unseen\\.(-?\\d+)\\.(\\d+)");
    private static final Set<String> PENDING_LISTENER_OPENS = new HashSet<>();

    private SavedNotificationDestinationResolver() {}

    public enum OpenResult {
        OPENED,
        ENABLE_RETRY_STARTED,
        APP_NOT_INSTALLED,
        APP_DISABLED_CANNOT_ENABLE,
        LISTENER_RETRY_STARTED,
        NO_EXACT_TARGET;

        public boolean accepted() {
            return this == OPENED || this == ENABLE_RETRY_STARTED
                    || this == LISTENER_RETRY_STARTED;
        }
    }

    /** Exact shortcut identity and serializable route captured while a notification is live. */
    public static final class CapturedShortcutRoute {
        public final String shortcutId;
        public final String routeUri;

        CapturedShortcutRoute(@NonNull String shortcutId, @Nullable String routeUri) {
            this.shortcutId = shortcutId;
            this.routeUri = routeUri == null ? "" : routeUri;
        }
    }

    public static boolean hasExactTarget(@NonNull Context context,
                                         @Nullable NotificationHistoryRecord record) {
        if (record == null) return false;
        if (!TextUtils.isEmpty(record.notificationId)
                && (NotificationListener.isNotificationActive(
                context, record.notificationId, record.postTime)
                || NotificationListener.hasRetainedContentIntent(
                record.notificationId, record.postTime)
                || NotificationListener.hasExactActiveContentIntent(
                record))) {
            return true;
        }
        return !NotificationPendingIntentStore.findAvailableToken(
                context, record.pendingIntentToken, record.notificationId, record.postTime).isEmpty()
                || !TextUtils.isEmpty(record.routeUri)
                || hasRecoverableKnownRoute(record)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && (!TextUtils.isEmpty(record.shortcutId)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && !TextUtils.isEmpty(record.locusId))));
    }

    /**
     * Open only an exact notification destination. This method never falls back to an app's main
     * launcher activity. When the posting app is frozen/disabled, Smart S first enables it and
     * defers exact-route resolution until PackageManager has made the package launchable again.
     * Returning true in that case means the verified exact-open retry has been accepted; callers
     * must not show a premature failure message while Android is still completing the unfreeze.
     */
    public static boolean openExact(@NonNull Context context,
                                    @Nullable NotificationHistoryRecord record) {
        return openExactResult(context, record).accepted();
    }

    @NonNull
    public static OpenResult openExactResult(@NonNull Context context,
                                             @Nullable NotificationHistoryRecord record) {
        if (record == null) return OpenResult.NO_EXACT_TARGET;

        if (!TextUtils.isEmpty(record.packageName)) {
            if (!AppLaunchUtils.isPackageInstalled(context, record.packageName)) {
                return OpenResult.APP_NOT_INSTALLED;
            }
            // Do not trust the short enabled-state cache here: the user may have frozen the app
            // seconds after it was last observed as enabled. Force a current PackageManager read.
            AppLaunchUtils.invalidatePackageState(record.packageName);
            if (!AppLaunchUtils.isPackageEnabled(context, record.packageName)) {
                if (!AppLaunchUtils.ensurePackageEnabled(context, record.packageName)) {
                    return OpenResult.APP_DISABLED_CANNOT_ENABLE;
                }
                scheduleExactOpenAfterEnable(context.getApplicationContext(), record, 0,
                        ENABLE_SETTLE_DELAY_MS);
                return OpenResult.ENABLE_RETRY_STARTED;
            }
        }

        if (openExactNow(context, record)) return OpenResult.OPENED;
        return scheduleExactOpenAfterListenerReconnect(context, record)
                ? OpenResult.LISTENER_RETRY_STARTED : OpenResult.NO_EXACT_TARGET;
    }

    private static boolean openExactNow(@NonNull Context context,
                                        @NonNull NotificationHistoryRecord record) {
        // The posting application's original notification tap route is authoritative. Reuse its
        // Android-managed relay before trying semantically equivalent durable shortcut routes.
        if (!TextUtils.isEmpty(record.notificationId)
                && NotificationListener.openNotification(context, record)) {
            return true;
        }
        return openDurableFallback(context, record);
    }

    /** Try only exact persisted fallbacks after an original notification PendingIntent expires. */
    public static boolean openDurableFallback(@NonNull Context context,
                                              @Nullable NotificationHistoryRecord record) {
        if (record == null) return false;
        if (openPublishedShortcut(context, record)) return true;
        if (TextUtils.isEmpty(record.routeUri)) recoverKnownDurableRoute(context, record);
        return openPersistedRoute(context, record);
    }

    /**
     * A package replacement can leave notification access granted while Android is still rebinding
     * the listener. Keep the user's exact-open request pending instead of reporting that the route
     * is missing before Android makes the active notification available again.
     */
    /**
     * Queue the existing listener-reconnect retry without re-entering openExact(). Notification
     * clicks use this only after their immediate exact-route attempts fail while listener state is
     * unverified, so accepting the retry suppresses a premature "destination unavailable" toast.
     */
    public static boolean scheduleExactOpenAfterListenerReconnectIfNeeded(
            @NonNull Context context, @Nullable NotificationHistoryRecord record) {
        return record != null
                && scheduleExactOpenAfterListenerReconnect(context, record);
    }

    private static boolean scheduleExactOpenAfterListenerReconnect(
            @NonNull Context context, @NonNull NotificationHistoryRecord record) {
        if (TextUtils.isEmpty(record.notificationId)
                || NotificationListener.isReadyForExactNotificationLookup()
                || !NotificationListener.requestListenerReconnect(context)) {
            return false;
        }

        String retryKey = listenerRetryKey(record);
        synchronized (PENDING_LISTENER_OPENS) {
            if (!PENDING_LISTENER_OPENS.add(retryKey)) return true;
        }
        Context appContext = context.getApplicationContext();
        Toast.makeText(appContext, "Restoring notification link…", Toast.LENGTH_SHORT).show();
        scheduleListenerRetry(appContext, record, retryKey, 0);
        return true;
    }

    private static void scheduleListenerRetry(@NonNull Context context,
                                              @NonNull NotificationHistoryRecord record,
                                              @NonNull String retryKey,
                                              int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (NotificationListener.isReadyForExactNotificationLookup()) {
                boolean opened = openExactNow(context, record);
                finishListenerRetry(retryKey);
                if (!opened) showListenerRecoveryFailure(context);
                return;
            }
            if (attempt + 1 < LISTENER_RETRY_COUNT) {
                NotificationListener.requestListenerReconnect(context);
                scheduleListenerRetry(context, record, retryKey, attempt + 1);
                return;
            }
            finishListenerRetry(retryKey);
            showListenerRecoveryFailure(context);
        }, LISTENER_RETRY_DELAY_MS);
    }

    private static void showListenerRecoveryFailure(@NonNull Context context) {
        Toast.makeText(context, "Exact notification/message link could not be restored.",
                Toast.LENGTH_SHORT).show();
    }

    private static String listenerRetryKey(@NonNull NotificationHistoryRecord record) {
        return record.notificationId + '|' + record.postTime + '|' + record.dbId;
    }

    private static void finishListenerRetry(@NonNull String retryKey) {
        synchronized (PENDING_LISTENER_OPENS) {
            PENDING_LISTENER_OPENS.remove(retryKey);
        }
    }

    /**
     * Recover exact routes for apps whose notification identity itself exposes a documented,
     * durable message identifier. This never derives a destination from notification title/body.
     *
     * FairEmail posts message notifications with a tag shaped as unseen.<group>.<messageId>.
     * Smart S persists StatusBarNotification#getKey() (Base64 encoded) as notificationId, so an
     * existing history row still contains that exact message ID even after Android invalidates the
     * original PendingIntent when the app is frozen. FairEmail's exported ActivityMain accepts the
     * stable message://email.faircode.eu#<messageId> route. Persist the recovered URI immediately
     * so subsequent opens no longer depend on Android's notification token or key format.
     */
    private static boolean recoverKnownDurableRoute(@NonNull Context context,
                                                    @NonNull NotificationHistoryRecord record) {
        if (!FAIREMAIL_PACKAGE.equals(record.packageName)
                || TextUtils.isEmpty(record.notificationId)) return false;

        String prefix = NotificationListener.NOTIFICATION_SCHEME;
        if (!record.notificationId.startsWith(prefix)) return false;
        String encodedKey = record.notificationId.substring(prefix.length());
        if (TextUtils.isEmpty(encodedKey)) return false;

        final String notificationKey;
        try {
            notificationKey = new String(Base64.decode(encodedKey,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Unable to decode saved FairEmail notification key", e);
            return false;
        }

        Matcher matcher = FAIREMAIL_UNSEEN_TAG.matcher(notificationKey);
        if (!matcher.find()) return false;

        final long messageId;
        try {
            messageId = Long.parseLong(matcher.group(2));
        } catch (NumberFormatException e) {
            return false;
        }
        if (messageId <= 0L) return false;

        Uri data = new Uri.Builder()
                .scheme("message")
                .authority("email.faircode.eu")
                .fragment(Long.toString(messageId))
                .build();
        Intent exact = new Intent(Intent.ACTION_VIEW, data)
                .setPackage(FAIREMAIL_PACKAGE)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addCategory(Intent.CATEGORY_BROWSABLE);
        String route = exact.toUri(Intent.URI_INTENT_SCHEME);
        if (TextUtils.isEmpty(route)) return false;

        record.routeUri = route;
        if (record.dbId > 0L) SmartStateStore.updateNotificationRoute(context, record.dbId, route);
        return true;
    }

    private static boolean hasRecoverableKnownRoute(@NonNull NotificationHistoryRecord record) {
        if (!FAIREMAIL_PACKAGE.equals(record.packageName)
                || TextUtils.isEmpty(record.notificationId)
                || !record.notificationId.startsWith(NotificationListener.NOTIFICATION_SCHEME)) {
            return false;
        }
        String encodedKey = record.notificationId.substring(
                NotificationListener.NOTIFICATION_SCHEME.length());
        try {
            String notificationKey = new String(Base64.decode(encodedKey,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
            Matcher matcher = FAIREMAIL_UNSEEN_TAG.matcher(notificationKey);
            if (!matcher.find()) return false;
            return Long.parseLong(matcher.group(2)) > 0L;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Re-resolve the exact destination after an unfreeze. ShortcutInfo is deliberately looked up
     * again on every attempt rather than retaining the pre-freeze object, and NotificationListener
     * reuses the posting app's retained PendingIntent when that is the exact route instead.
     */
    private static void scheduleExactOpenAfterEnable(@NonNull Context context,
                                                     @NonNull NotificationHistoryRecord record,
                                                     int attempt,
                                                     long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!TextUtils.isEmpty(record.packageName)) {
                AppLaunchUtils.invalidatePackageState(record.packageName);
                if (!AppLaunchUtils.isPackageEnabled(context, record.packageName)) {
                    if (attempt + 1 < EXACT_RETRY_COUNT) {
                        scheduleExactOpenAfterEnable(context, record, attempt + 1,
                                EXACT_RETRY_DELAY_MS);
                    } else {
                        Log.w(TAG, "Posting app did not become enabled in time for exact notification route");
                    }
                    return;
                }
            }

            if (openExactNow(context, record)) return;
            if (scheduleExactOpenAfterListenerReconnect(context, record)) return;
            if (attempt + 1 < EXACT_RETRY_COUNT) {
                scheduleExactOpenAfterEnable(context, record, attempt + 1,
                        EXACT_RETRY_DELAY_MS);
            } else {
                Log.w(TAG, "Exact notification route remained unavailable after app unfreeze");
            }
        }, delayMs);
    }

    @Nullable
    public static CapturedShortcutRoute capturePublishedShortcutRoute(
            @NonNull Context context,
            @NonNull String packageName,
            @Nullable String shortcutId,
            @Nullable String locusId,
            @Nullable android.os.UserHandle user) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || TextUtils.isEmpty(packageName) || user == null) return null;
        ShortcutInfo shortcut = TextUtils.isEmpty(shortcutId) ? null
                : ShortcutUtil.getShortCut(context, user, packageName, shortcutId);
        if (shortcut == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && !TextUtils.isEmpty(locusId)) {
            shortcut = findShortcutByLocus(context, user, packageName, locusId);
        }
        if (shortcut == null) return null;
        return new CapturedShortcutRoute(shortcut.getId(),
                routeUriFromShortcut(context, shortcut, packageName));
    }

    private static boolean openPersistedRoute(@NonNull Context context,
                                              @NonNull NotificationHistoryRecord record) {
        if (TextUtils.isEmpty(record.routeUri) || TextUtils.isEmpty(record.packageName)) return false;
        if (record.userSerial >= 0L) {
            UserManager userManager = ContextCompat.getSystemService(context, UserManager.class);
            if (userManager == null) return false;
            long currentSerial = userManager.getSerialNumberForUser(android.os.Process.myUserHandle());
            if (currentSerial != record.userSerial) return false;
        }
        try {
            Intent intent = Intent.parseUri(record.routeUri, Intent.URI_INTENT_SCHEME);
            if (!isRouteOwnedByPackage(context, intent, record.packageName)) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (java.net.URISyntaxException | RuntimeException e) {
            Log.w(TAG, "Unable to open persisted exact notification route", e);
            return false;
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Nullable
    private static String routeUriFromShortcut(@NonNull Context context,
                                               @NonNull ShortcutInfo shortcut,
                                               @NonNull String expectedPackage) {
        Intent[] intents = shortcut.getIntents();
        if (intents == null) return null;
        for (int i = intents.length - 1; i >= 0; i--) {
            Intent candidate = intents[i];
            if (candidate == null || !isRouteOwnedByPackage(context, candidate, expectedPackage)) {
                continue;
            }
            return candidate.toUri(Intent.URI_INTENT_SCHEME);
        }
        return null;
    }

    private static boolean isRouteOwnedByPackage(@NonNull Context context,
                                                 @NonNull Intent intent,
                                                 @NonNull String expectedPackage) {
        if (intent.getComponent() != null) {
            return TextUtils.equals(expectedPackage, intent.getComponent().getPackageName());
        }
        if (!TextUtils.isEmpty(intent.getPackage())) {
            return TextUtils.equals(expectedPackage, intent.getPackage());
        }
        try {
            ResolveInfo resolved = context.getPackageManager().resolveActivity(
                    intent, PackageManager.MATCH_DEFAULT_ONLY);
            return resolved != null && resolved.activityInfo != null
                    && TextUtils.equals(expectedPackage, resolved.activityInfo.packageName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private static void persistResolvedShortcut(@NonNull Context context,
                                                @NonNull NotificationHistoryRecord record,
                                                @NonNull ShortcutInfo shortcut) {
        String route = routeUriFromShortcut(context, shortcut, record.packageName);
        boolean shortcutChanged = !TextUtils.equals(record.shortcutId, shortcut.getId());
        boolean routeChanged = !TextUtils.isEmpty(route)
                && !TextUtils.equals(record.routeUri, route);
        if (!shortcutChanged && !routeChanged) return;
        record.shortcutId = shortcut.getId();
        if (routeChanged) record.routeUri = route;
        SmartStateStore.updateNotificationShortcutRoute(
                context, record.dbId, record.shortcutId, record.routeUri);
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
                || (TextUtils.isEmpty(record.shortcutId)
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || TextUtils.isEmpty(record.locusId)))) {
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
                        record.packageName, record.shortcutId, record.locusId);
                if (preferred != null) break;
            }
            if (preferred != null) {
                persistResolvedShortcut(context, record, preferred);
                return launch(launcherApps, preferred);
            }
        }

        // A restored backup can land on a phone whose Android profile serials differ. Fall back
        // only when exactly one accessible profile publishes the saved shortcut; never guess when
        // the same route exists in multiple profiles.
        List<ShortcutInfo> matches = new ArrayList<>(2);
        for (android.os.UserHandle profile : profiles) {
            ShortcutInfo shortcut = findShortcut(context, userManager, profile,
                    record.packageName, record.shortcutId, record.locusId);
            if (shortcut != null) matches.add(shortcut);
            if (matches.size() > 1) return false;
        }
        if (matches.size() != 1) return false;
        persistResolvedShortcut(context, record, matches.get(0));
        return launch(launcherApps, matches.get(0));
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Nullable
    private static ShortcutInfo findShortcut(@NonNull Context context,
                                             @NonNull UserManager userManager,
                                             @NonNull android.os.UserHandle profile,
                                             @NonNull String packageName,
                                             @Nullable String shortcutId,
                                             @Nullable String locusId) {
        try {
            if (!userManager.isUserRunning(profile) || !userManager.isUserUnlocked(profile)) return null;
            ShortcutInfo shortcut = TextUtils.isEmpty(shortcutId) ? null
                    : ShortcutUtil.getShortCut(context, profile, packageName, shortcutId);
            if (shortcut != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    || TextUtils.isEmpty(locusId)) return shortcut;
            return findShortcutByLocus(context, profile, packageName, locusId);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to resolve saved notification shortcut", e);
            return null;
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Nullable
    private static ShortcutInfo findShortcutByLocus(@NonNull Context context,
                                                    @NonNull android.os.UserHandle profile,
                                                    @NonNull String packageName,
                                                    @NonNull String locusId) {
        ShortcutInfo match = null;
        try {
            for (ShortcutInfo candidate : ShortcutUtil.getShortcuts(context, packageName)) {
                if (candidate == null || !candidate.isEnabled()
                        || !profile.equals(candidate.getUserHandle())
                        || candidate.getLocusId() == null
                        || !TextUtils.equals(locusId, candidate.getLocusId().getId())) {
                    continue;
                }
                if (match != null && !TextUtils.equals(match.getId(), candidate.getId())) return null;
                match = candidate;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to resolve notification shortcut locus", e);
            return null;
        }
        return match;
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
