package fr.neamar.kiss.notification;

import android.app.Application;
import android.content.Context;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import java.util.concurrent.atomic.AtomicLong;
import fr.neamar.kiss.appusage.AppUsageTracker;
import fr.neamar.kiss.battery.BatteryMonitorStarter;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.utils.LauncherScrollWorkGate;

/** Automatic optional work follows current settings; live UI remains available on demand. */
public final class NotificationWorkPolicy {
    private static final AtomicLong GENERATION = new AtomicLong();
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;
    private NotificationWorkPolicy() { }

    public static boolean historyEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("enable-notification-history", false);
    }

    public static boolean captureAvatars(Context context) {
        return historyEnabled(context);
    }

    public static void setHomeVisible(boolean visible) {
        if (!visible) LauncherScrollWorkGate.signalPolicyChanged();
    }
    public static long generation() { return GENERATION.get(); }

    /** Unbind when history is off; keep Android notification access for re-enabling. */
    public static void syncListener(Context context) {
        Context app = context.getApplicationContext();
        if (!historyEnabled(app)) {
            NotificationListener.stopForDisabledHistory(app);
            return;
        }
        PackageManager pm = app.getPackageManager();
        ComponentName component = new ComponentName(app, NotificationListener.class);
        int current = pm.getComponentEnabledSetting(component);
        if (current != PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                && current != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        }
        NotificationListener.requestListenerReconnect(app);
    }

    /** Event-driven cleanup; enabled features and explicitly requested work are left alone. */
    public static void onScreenOff(Context context) {
        if (!historyEnabled(context)) syncListener(context);
        AppUsageTracker.stopDisabledWork(context);
        if (!BatteryMonitorStarter.isEnabled(context)) BatteryMonitorStarter.stopRunning(context);
        fr.neamar.kiss.searcher.AppMetadataSyncScheduler.deferPendingWhileScreenOff(context);
    }

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
                syncListener(app);
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
        if (!BatteryMonitorStarter.isEnabled(app)) BatteryMonitorStarter.stopRunning(app);
        syncListener(app);
    }
}
