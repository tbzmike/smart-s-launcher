package fr.neamar.kiss.notification;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import fr.neamar.kiss.utils.Log;

/**
 * Keeps the posting application's original notification PendingIntent in Android's own
 * PendingIntent registry. The database stores only the opaque identity token; no private app
 * destination is guessed or reconstructed from notification text.
 */
public final class NotificationPendingIntentStore {
    private static final String TAG = NotificationPendingIntentStore.class.getSimpleName();
    static final String EXTRA_TARGET =
            "com.tbzmike.smartslauncher.notification.TARGET_PENDING_INTENT";
    static final String EXTRA_NOTIFICATION_ID =
            "com.tbzmike.smartslauncher.notification.NOTIFICATION_ID";
    static final String EXTRA_POST_TIME =
            "com.tbzmike.smartslauncher.notification.POST_TIME";
    static final String EXTRA_ROUTE_TOKEN =
            "com.tbzmike.smartslauncher.notification.ROUTE_TOKEN";

    private NotificationPendingIntentStore() {}

    @NonNull
    public static String capture(@NonNull Context context,
                                 @NonNull String notificationId,
                                 long postTime,
                                 @Nullable PendingIntent target) {
        if (target == null) return "";
        String token = NotificationRouteIdentity.create(notificationId, postTime);
        if (!NotificationRouteIdentity.isValid(token)) return "";
        try {
            PendingIntent relay = PendingIntent.getActivity(
                    context.getApplicationContext(),
                    NotificationRouteIdentity.requestCode(token),
                    relayIntent(context, token)
                            .putExtra(EXTRA_TARGET, target)
                            .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                            .putExtra(EXTRA_POST_TIME, postTime),
                    flags(PendingIntent.FLAG_UPDATE_CURRENT));
            return relay == null ? "" : token;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to retain exact notification PendingIntent", e);
            return "";
        }
    }

    public static boolean has(@NonNull Context context, @Nullable String token) {
        return find(context, token) != null;
    }

    /**
     * Resolve a retained route even when an earlier database refresh dropped its token. Route
     * identities are deterministic for one exact notification event, so the Android-managed relay
     * can be rediscovered from the persisted notification id and post time without guessing a
     * destination or opening a different message.
     */
    @NonNull
    public static String findAvailableToken(@NonNull Context context,
                                            @Nullable String persistedToken,
                                            @Nullable String notificationId,
                                            long postTime) {
        if (persistedToken != null && has(context, persistedToken)) return persistedToken;
        if (postTime <= 0L) return "";

        String recoveredToken = NotificationRouteIdentity.create(notificationId, postTime);
        if (!NotificationRouteIdentity.isValid(recoveredToken)
                || recoveredToken.equals(persistedToken)) {
            return "";
        }
        return has(context, recoveredToken) ? recoveredToken : "";
    }

    public static boolean open(@NonNull Context context, @Nullable String token) {
        PendingIntent relay = find(context, token);
        if (relay == null) return false;
        try {
            relay.send();
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            relay.cancel();
            Log.w(TAG, "Saved notification relay is no longer available", e);
            return false;
        }
    }

    static boolean sendTarget(@NonNull Context context, @NonNull PendingIntent target) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions options = ActivityOptions.makeBasic();
                if (Build.VERSION.SDK_INT >= 36) {
                    options.setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE);
                } else {
                    options.setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                }
                target.send(context, 0, null, null, null, null, options.toBundle());
            } else {
                target.send();
            }
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            Log.w(TAG, "Original notification destination could not be opened", e);
            return false;
        }
    }

    public static void discard(@NonNull Context context, @Nullable String token) {
        PendingIntent relay = find(context, token);
        if (relay != null) relay.cancel();
    }

    @Nullable
    private static PendingIntent find(@NonNull Context context, @Nullable String token) {
        if (!NotificationRouteIdentity.isValid(token)) return null;
        try {
            return PendingIntent.getActivity(
                    context.getApplicationContext(),
                    NotificationRouteIdentity.requestCode(token),
                    relayIntent(context, token),
                    flags(PendingIntent.FLAG_NO_CREATE));
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to resolve saved notification relay", e);
            return null;
        }
    }

    @NonNull
    private static Intent relayIntent(@NonNull Context context, @NonNull String token) {
        Uri identity = new Uri.Builder()
                .scheme(context.getPackageName())
                .authority("saved-notification")
                .appendPath(token)
                .build();
        return new Intent(context, NotificationRouteRelayActivity.class)
                .setAction(context.getPackageName() + ".OPEN_SAVED_NOTIFICATION_ROUTE")
                .setData(identity)
                .putExtra(EXTRA_ROUTE_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }

    private static int flags(int base) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? base | PendingIntent.FLAG_IMMUTABLE : base;
    }
}
