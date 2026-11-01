package fr.neamar.kiss.notification;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import java.util.concurrent.atomic.AtomicLong;
import fr.neamar.kiss.appusage.AppUsageTracker;
import fr.neamar.kiss.battery.BatteryMonitorService;
import fr.neamar.kiss.battery.BatteryMonitorStarter;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.utils.LauncherScrollWorkGate;

/** Automatic optional work follows current settings; live UI remains available on demand. */
public final class NotificationWorkPolicy {
    private static volatile boolean homeVisible;
    private static final AtomicLong GENERATION = new AtomicLong();
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;
    private NotificationWorkPolicy() { }

    public static boolean historyEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("enable-notification-history", false);
    }

    public static boolean captureAvatars(Context context) {
        return historyEnabled(context) || homeVisible;
    }

    public static void setHomeVisible(boolean visible) {
        homeVisible = visible;
        if (!visible) LauncherScrollWorkGate.signalPolicyChanged();
    }
    public static long generation() { return GENERATION.get(); }

    public static void install(Application app) {
        if (listener != null) return;
        listener = (prefs, key) -> {
            if ("enable-notification-history".equals(key)) {
                GENERATION.incrementAndGet();
                LauncherScrollWorkGate.signalPolicyChanged();
                NotificationVisualSupport.cancelPendingCapture();
                NotificationAvatarSupport.onHistorySettingChanged(app);
                MediaHistoryCoordinator.clearCaptureState();
                if (!historyEnabled(app)) MediaNotificationSupport.clearMemoryCache();
                SmartStateStore.clearNotificationMemoryCache();
            } else if (AppUsageTracker.PREF_ENABLED.equals(key)) {
                AppUsageTracker.stopDisabledWork(app);
            } else if (BatteryMonitorStarter.PREF_ENABLED.equals(key)
                    && !BatteryMonitorStarter.isEnabled(app)) {
                BatteryMonitorStarter.stopRunning(app);
            }
        };
        PreferenceManager.getDefaultSharedPreferences(app)
                .registerOnSharedPreferenceChangeListener(listener);
        NotificationAvatarSupport.onHistorySettingChanged(app);
        if (!historyEnabled(app)) MediaNotificationSupport.clearMemoryCache();
        AppUsageTracker.stopDisabledWork(app);
    }
}
