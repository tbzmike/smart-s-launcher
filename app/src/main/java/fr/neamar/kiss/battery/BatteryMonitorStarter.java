package fr.neamar.kiss.battery;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

public final class BatteryMonitorStarter {
    public static final String PREF_ENABLED = "smart-battery-monitor-enabled";

    private BatteryMonitorStarter() {}

    public static void setEnabled(Context context, boolean enabled) {
        Context appContext = context.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
                .putBoolean(PREF_ENABLED, enabled)
                .apply();
        if (enabled) {
            ensureRunning(appContext);
        } else {
            try {
                appContext.startService(new Intent(appContext, BatteryMonitorService.class)
                        .setAction(BatteryMonitorService.ACTION_STOP));
            } catch (RuntimeException ignored) {
                appContext.stopService(new Intent(appContext, BatteryMonitorService.class));
            }
        }
    }

    public static void ensureRunning(Context context) {
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_ENABLED, true)) return;
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
