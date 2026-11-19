package fr.neamar.kiss.battery;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

public final class BatteryMonitorStarter {
    public static final String PREF_ENABLED = "smart-battery-monitor-enabled";
    // Widgets and activities can request monitoring at the same time. Treat service startup as
    // an idempotent state transition: one pending start, then one running service, not repeated
    // startForegroundService calls that keep resetting its sampling schedule.
    private static final BatteryMonitorStartGate START_GATE = new BatteryMonitorStartGate();

    private BatteryMonitorStarter() {}

    public static void setEnabled(Context context, boolean enabled) {
        Context appContext = context.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
                .putBoolean(PREF_ENABLED, enabled)
                .apply();
        if (enabled) {
            ensureRunning(appContext);
        } else {
            stopRunning(appContext);
        }
    }

    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_ENABLED, false);
    }

    public static void stopRunning(Context context) {
        // Android must deliver an outstanding startForegroundService request before it can be
        // stopped safely. The service acknowledges that request and immediately stops if OFF.
        if (!START_GATE.isStartPending()) {
            context.stopService(new Intent(context, BatteryMonitorService.class));
        }
    }

    static void startHandled() { START_GATE.onStartHandled(); }

    static void serviceStopped() { START_GATE.onServiceStopped(); }

    public static void ensureRunning(Context context) {
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_ENABLED, false)) return;
        if (!START_GATE.requestStart()) return;
        Intent intent = new Intent(context, BatteryMonitorService.class)
                .setAction(BatteryMonitorService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
            START_GATE.onStartFailed();
            // Android may temporarily block background FGS starts. The next launcher/widget foreground
            // interaction will retry without crashing the launcher.
        }
    }
}
