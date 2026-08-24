package fr.neamar.kiss.appusage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

public final class AppUsageBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AppUsageTracker.isEnabled(context)) return;
        Context appContext = context.getApplicationContext();
        long now = System.currentTimeMillis();
        long bootTime = Math.max(0L, now - SystemClock.elapsedRealtime());
        AppUsageStore.get(appContext).putTimeline(new AppUsageStore.TimelineEntry(
                "device-boot:" + bootTime,
                bootTime,
                0L,
                "DEVICE_BOOT",
                null,
                null,
                0L,
                false,
                "Device boot",
                null,
                null));
        AppUsageTracker.ensureScheduled(appContext);
        AppUsageTracker.syncAsync(appContext);
    }
}
