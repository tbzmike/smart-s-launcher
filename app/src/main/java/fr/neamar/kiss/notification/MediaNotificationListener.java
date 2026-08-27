package fr.neamar.kiss.notification;

import android.service.notification.StatusBarNotification;

/** Extends the existing listener only to snapshot media artwork for launcher history. */
public final class MediaNotificationListener extends NotificationListener {
    @Override public void onListenerConnected() {
        super.onListenerConnected();
        StatusBarNotification[] active;
        try { active = getActiveNotifications(); }
        catch (RuntimeException e) { return; }
        if (active == null) return;
        for (StatusBarNotification sbn : active) MediaNotificationSupport.capture(this, sbn);
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        MediaNotificationSupport.capture(this, sbn);
    }
}
