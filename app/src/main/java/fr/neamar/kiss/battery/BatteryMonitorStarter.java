package fr.neamar.kiss.battery;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

public final class BatteryMonitorStarter {
    private BatteryMonitorStarter() {}

    public static void ensureRunning(Context context) {
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("smart-battery-monitor-enabled", true)) return;
        Intent intent = new Intent(context, BatteryMonitorService.class)
                .setAction(BatteryMonitorService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
            // Android may temporarily block background FGS starts. The next launcher/widget foreground
            // interaction will retry without crashing the launcher.
        }
    }
}
