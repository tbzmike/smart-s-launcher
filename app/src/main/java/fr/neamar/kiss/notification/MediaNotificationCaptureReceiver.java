package fr.neamar.kiss.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.Field;

import fr.neamar.kiss.utils.Log;

/**
 * Captures media artwork whenever the existing NotificationListener publishes its LOAD_OVER
 * refresh. Keeping the original listener component name preserves the user's notification-access
 * grant across upgrades.
 */
public final class MediaNotificationCaptureReceiver extends BroadcastReceiver {
    private static final String TAG = MediaNotificationCaptureReceiver.class.getSimpleName();

    @Override public void onReceive(Context context, Intent intent) {
        NotificationListener listener = listenerInstance();
        if (listener == null) return;
        StatusBarNotification[] active;
        try { active = listener.getActiveNotifications(); }
        catch (RuntimeException e) {
            Log.w(TAG, "Unable to snapshot active media notifications", e);
            return;
        }
        if (active == null) return;
        Context app = context.getApplicationContext();
        for (StatusBarNotification sbn : active) MediaNotificationSupport.capture(app, sbn);
    }

    private static NotificationListener listenerInstance() {
        try {
            Field field = NotificationListener.class.getDeclaredField("instance");
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof NotificationListener ? (NotificationListener) value : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
