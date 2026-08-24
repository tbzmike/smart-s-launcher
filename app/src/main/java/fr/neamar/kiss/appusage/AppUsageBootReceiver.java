package fr.neamar.kiss.appusage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AppUsageBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AppUsageTracker.isEnabled(context)) return;
        AppUsageTracker.ensureScheduled(context.getApplicationContext());
        AppUsageTracker.syncAsync(context.getApplicationContext());
    }
}
