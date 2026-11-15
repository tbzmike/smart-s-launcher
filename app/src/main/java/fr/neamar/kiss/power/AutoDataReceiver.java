package fr.neamar.kiss.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System-delivered power/boot events and the single explicit screen-off alarm. */
public final class AutoDataReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AutoDataController.onSystemEvent(context, intent == null ? null : intent.getAction());
    }
}
