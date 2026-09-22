package fr.neamar.kiss.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** User-tapped notification action that safely hands the verified update to Android. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    public static final String ACTION_INSTALL =
            "com.tbzmike.smartslauncher.action.INSTALL_VERIFIED_UPDATE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_INSTALL.equals(intent.getAction())) return;
        AppUpdater.installReadyUpdate(context.getApplicationContext());
    }
}
