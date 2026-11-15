package fr.neamar.kiss.power;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.provider.Settings;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.utils.Log;

/**
 * Opt-in, root-backed mobile-data saver. NO service, polling loop, repeated timer or wake lock.
 * Screen transitions are delivered to the one app-process receiver; an explicit alarm can wake
 * the process for the selected delay. Power/USB connections temporarily suspend the feature.
 */
public final class AutoDataController {
    public static final String PREF_ENABLED = "auto-data-enabled";
    public static final String PREF_DELAY = "auto-data-delay-minutes";
    public static final String PREF_STATUS = "auto-data-last-status";
    public static final String ACTION_DISABLE = "fr.neamar.kiss.power.AUTO_DATA_DISABLE";
    private static final String PREF_DUE = "auto-data-off-at-elapsed";
    private static final String PREF_OWNED = "auto-data-disabled-by-smart-s";
    private static final String USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final String TAG = "SmartS-AutoData";

    // One short-lived worker for root commands. Core threads time out between state changes.
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
            1, 1, 15L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
            job -> {
                Thread thread = new Thread(job, "smart-s-auto-data");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final Object INSTALL_LOCK = new Object();
    private static Context application;
    private static BroadcastReceiver screenAndUsbReceiver;
    private static SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    static {
        WORKER.allowCoreThreadTimeOut(true);
    }

    private AutoDataController() { }

    /** Register only once, irrespective of launcher Activity recreation/rotation. */
    public static void install(Context context) {
        Context app = context.getApplicationContext();
        synchronized (INSTALL_LOCK) {
            if (application != null) return;
            application = app;
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(USB_STATE);
            screenAndUsbReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    enqueue(app, intent == null ? null : intent.getAction());
                }
            };
            ContextCompat.registerReceiver(app, screenAndUsbReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);

            prefListener = (prefs, key) -> {
                if (PREF_ENABLED.equals(key) || PREF_DELAY.equals(key)) {
                    enqueue(app, "preference");
                }
            };
            PreferenceManager.getDefaultSharedPreferences(app)
                    .registerOnSharedPreferenceChangeListener(prefListener);
        }
        enqueue(app, "start");
    }

    /** Called by the manifest receiver, even after process death, boot or APK replacement. */
    public static void onSystemEvent(Context context, String action) {
        enqueue(context.getApplicationContext(), action);
    }

    /** A foreground HOME resume guarantees repair if the process missed SCREEN_ON. */
    public static void onHomeResumed(Context context) {
        enqueue(context.getApplicationContext(), Intent.ACTION_SCREEN_ON);
    }

    /** Explicit settings action: request/probe su while the user can approve its dialog. */
    public static void requestRootCheck(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            boolean granted = KissApplication.getApplication(app)
                    .getRootHandler().isRootAvailable();
            setStatus(app, granted
                    ? "Root access is available. Auto Data is ready."
                    : "Root access is unavailable. Grant Smart S su access to use Auto Data.");
        });
    }

    public static String getStatus(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(PREF_ENABLED, false)) {
            return "Off. Mobile data is unchanged unless Smart S must restore an earlier toggle.";
        }
        String status = prefs.getString(PREF_STATUS, "");
        return status.isEmpty()
                ? "Enabled. Needs root permission; waits for the selected screen-off delay."
                : status;
    }

    /** Re-evaluate live state; discarded intermediate events are superseded by the latest event. */
    private static void enqueue(Context app, String action) {
        WORKER.getQueue().clear();
        WORKER.execute(() -> {
            try {
                reconcile(app, action);
            } catch (RuntimeException failure) {
                Log.w(TAG, "Auto Data state reconciliation failed", failure);
                setStatus(app, "Unable to change mobile data; check root permission.");
            }
        });
    }

    private static void reconcile(Context app, String action) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // ElapsedRealtime from the previous boot is no longer a valid scheduled instant.
            prefs.edit().remove(PREF_DUE).apply();
        }

        boolean enabled = prefs.getBoolean(PREF_ENABLED, false);
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        boolean interactive = power == null || power.isInteractive();
        boolean externallyConnected = externalPowerOrUsb(app);

        if (AutoDataPolicy.shouldRestore(prefs.getBoolean(PREF_OWNED, false),
                enabled, interactive, externallyConnected)) {
            cancelAlarm(app);
            prefs.edit().remove(PREF_DUE).apply();
            restoreIfOwned(app, prefs);
        }

        if (!AutoDataPolicy.shouldSchedule(enabled, interactive, externallyConnected)) {
            cancelAlarm(app);
            prefs.edit().remove(PREF_DUE).apply();
            if (enabled && externallyConnected) {
                setStatus(app, "Paused while USB or a charger is connected. Data is left on.");
            } else if (enabled) {
                setStatus(app, "Screen on. Waiting for screen off; data is restored if Smart S disabled it.");
            } else {
                setStatus(app, "Auto Data off.");
            }
            return;
        }

        // SCREEN_OFF and unplug both start a new countdown. All other events reuse one deadline.
        long now = SystemClock.elapsedRealtime();
        long due = prefs.getLong(PREF_DUE, 0L);
        if (Intent.ACTION_SCREEN_OFF.equals(action)
                || Intent.ACTION_POWER_DISCONNECTED.equals(action)
                || USB_STATE.equals(action) || "preference".equals(action)
                || due <= 0L || due > now + TimeUnit.MINUTES.toMillis(31)) {
            int minutes = AutoDataPolicy.delayMinutes(prefs.getInt(PREF_DELAY, 5));
            due = now + TimeUnit.MINUTES.toMillis(minutes);
            prefs.edit().putLong(PREF_DUE, due).apply();
        }
        if (due > now) {
            scheduleAlarm(app, due);
            setStatus(app, "Screen off: will switch mobile data off after the selected delay.");
            return;
        }
        cancelAlarm(app);
        if (prefs.getBoolean(PREF_OWNED, false)) {
            setStatus(app, "Mobile data switched off by Auto Data.");
            return;
        }
        Boolean wasEnabled = isMobileDataEnabled(app);
        if (wasEnabled == null) {
            setStatus(app, "Mobile-data state unavailable. No change made.");
            return;
        }
        if (!AutoDataPolicy.shouldDisable(enabled, interactive, externallyConnected,
                wasEnabled, false)) {
            setStatus(app, "Mobile data was already off; Auto Data has left it unchanged.");
            return;
        }

        // Record ownership before running su so process death cannot strand data OFF without
        // knowing who changed it. A failed root command removes ownership immediately.
        if (!prefs.edit().putBoolean(PREF_OWNED, true).commit()) {
            setStatus(app, "Unable to save restore state. Mobile data left unchanged.");
            return;
        }
        if (!KissApplication.getApplication(app).getRootHandler().setMobileDataEnabled(false)) {
            prefs.edit().putBoolean(PREF_OWNED, false).commit();
            setStatus(app, "Root unavailable or mobile data command failed. Data left unchanged.");
            return;
        }
        setStatus(app, "Mobile data switched off by Auto Data; restores on screen-on or USB/charger.");
        // A screen/USB event may have happened while su was executing. Recheck once, no polling.
        if (isInteractive(app) || externalPowerOrUsb(app)) {
            restoreIfOwned(app, prefs);
        }
    }

    private static void restoreIfOwned(Context app, SharedPreferences prefs) {
        if (!prefs.getBoolean(PREF_OWNED, false)) return;
        Boolean enabledNow = isMobileDataEnabled(app);
        if (Boolean.TRUE.equals(enabledNow)) {
            prefs.edit().putBoolean(PREF_OWNED, false).commit();
            return;
        }
        if (KissApplication.getApplication(app).getRootHandler().setMobileDataEnabled(true)) {
            prefs.edit().putBoolean(PREF_OWNED, false).commit();
            setStatus(app, "Mobile data restored by Auto Data.");
        } else {
            // Keep ownership for a later retry when root becomes available.
            setStatus(app, "Mobile data restore pending: grant Smart S root access.");
        }
    }

    private static boolean isInteractive(Context app) {
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        return power == null || power.isInteractive();
    }

    private static boolean externalPowerOrUsb(Context app) {
        Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) {
            return true;
        }
        // Includes USB data cables connected without charging.
        Intent usb = app.registerReceiver(null, new IntentFilter(USB_STATE));
        return usb != null && usb.getBooleanExtra("connected", false);
    }

    /** Read the user's mobile-data switch, not whether Wi-Fi currently carries traffic. */
    private static Boolean isMobileDataEnabled(Context app) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                TelephonyManager manager =
                        (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
                return manager == null ? null : manager.isDataEnabled();
            } catch (SecurityException | UnsupportedOperationException error) {
                return null;
            }
        }
        try {
            int value = Settings.Global.getInt(app.getContentResolver(), "mobile_data", -1);
            return value == -1 ? null : value != 0;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static PendingIntent offAlarm(Context app) {
        Intent alarm = new Intent(app, AutoDataReceiver.class).setAction(ACTION_DISABLE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(app, 196, alarm, flags);
    }

    private static void scheduleAlarm(Context app, long elapsedDue) {
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent intent = offAlarm(app);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDue, intent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDue, intent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Exact-alarm special access denied: no crash. Android may delay this in Doze.
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDue, intent);
        } else {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDue, intent);
        }
    }

    private static void cancelAlarm(Context app) {
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(offAlarm(app));
    }

    private static void setStatus(Context app, String message) {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(PREF_STATUS, message).apply();
    }
}
