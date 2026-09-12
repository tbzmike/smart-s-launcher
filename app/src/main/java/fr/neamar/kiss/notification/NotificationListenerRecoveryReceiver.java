package fr.neamar.kiss.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reconnects notification access after Android restarts or replaces Smart S Launcher. */
public final class NotificationListenerRecoveryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            NotificationListener.requestListenerReconnect(context);
        }
    }
}
