package fr.neamar.kiss.battery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.util.Locale;

import fr.neamar.kiss.BatteryHistoryActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.utils.Log;

public class BatteryMonitorService extends Service {
    public static final String ACTION_START = "fr.neamar.kiss.action.START_BATTERY_MONITOR";
    public static final String ACTION_STOP = "fr.neamar.kiss.action.STOP_BATTERY_MONITOR";
    public static final String ACTION_REFRESH = "fr.neamar.kiss.action.REFRESH_BATTERY_MONITOR";
    private static final String TAG = BatteryMonitorService.class.getSimpleName();
    private static final String CHANNEL_LIVE = "smart_battery_live";
    private static final String CHANNEL_ALERTS = "smart_battery_alerts";
    private static final int LIVE_ID = 7401;
    private static final int ALERT_ID = 7402;
    private static final long SCREEN_ON_INTERVAL_MS = 60_000L;
    private static final long SCREEN_OFF_INTERVAL_MS = 3L * 60_000L;
    private static final long RATE_CACHE_MS = 60_000L;
    private static final long WIDGET_REFRESH_MS = 15L * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable periodicSample = new Runnable() {
        @Override public void run() {
            sampleAndPublish(false);
            handler.postDelayed(this, isScreenOn() ? SCREEN_ON_INTERVAL_MS : SCREEN_OFF_INTERVAL_MS);
        }
    };
    private BatteryHistoryStore store;
    private BatteryRateCalculator.ScreenRates cachedRates;
    private boolean cachedRatesCharging;
    private boolean cachedRatesScreenOn;
    private long cachedRatesAtMs;
    private int lastWidgetPercent = Integer.MIN_VALUE;
    private boolean lastWidgetCharging;
    private long lastWidgetRefreshMs;

    @Override public void onCreate() {
        super.onCreate();
        store = new BatteryHistoryStore(this);
        createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean("smart-battery-monitor-enabled", false).apply();
            handler.removeCallbacks(periodicSample);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean("smart-battery-monitor-enabled", true).apply();
        BatterySnapshot snapshot = BatteryMonitorEngine.readSnapshot(this);
        startForeground(LIVE_ID, buildLiveNotification(snapshot));
        sampleAndPublish(true);
        handler.removeCallbacks(periodicSample);
        handler.postDelayed(periodicSample,
                isScreenOn() ? SCREEN_ON_INTERVAL_MS : SCREEN_OFF_INTERVAL_MS);
        return START_STICKY;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        handler.removeCallbacks(periodicSample);
        super.onDestroy();
    }

    private void sampleAndPublish(boolean forceWidgetRefresh) {
        BatterySnapshot snapshot = BatteryMonitorEngine.readSnapshot(this);
        boolean screenOn = isScreenOn();
        store.record(snapshot, screenOn);
        cachedRates = BatteryRateCalculator.calculate(store, snapshot,
                store.estimatedFullCapacityUah(), screenOn);
        cachedRatesCharging = snapshot.isCharging();
        cachedRatesScreenOn = screenOn;
        cachedRatesAtMs = System.currentTimeMillis();
        NotificationManager nm = notificationManager();
        if (nm != null) nm.notify(LIVE_ID, buildLiveNotification(snapshot));
        checkAlerts(snapshot);
        maybeRefreshWidgets(snapshot, forceWidgetRefresh);
    }

    private BatteryRateCalculator.ScreenRates calculateRates(BatterySnapshot snapshot) {
        boolean screenOn = isScreenOn();
        long now = System.currentTimeMillis();
        if (cachedRates != null
                && cachedRatesCharging == snapshot.isCharging()
                && cachedRatesScreenOn == screenOn
                && now - cachedRatesAtMs < RATE_CACHE_MS) {
            return cachedRates;
        }
        cachedRates = BatteryRateCalculator.calculate(store, snapshot,
                store.estimatedFullCapacityUah(), screenOn);
        cachedRatesCharging = snapshot.isCharging();
        cachedRatesScreenOn = screenOn;
        cachedRatesAtMs = now;
        return cachedRates;
    }

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private NotificationManager notificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private void maybeRefreshWidgets(BatterySnapshot s, boolean force) {
        long now = System.currentTimeMillis();
        boolean charging = s.isCharging();
        int percent = s.percent();
        if (!force && percent == lastWidgetPercent && charging == lastWidgetCharging
                && now - lastWidgetRefreshMs < WIDGET_REFRESH_MS) return;
        Intent refresh = new Intent("fr.neamar.kiss.action.BATTERY_MONITOR_UPDATED")
                .setPackage(getPackageName());
        sendBroadcast(refresh);
        lastWidgetRefreshMs = now;
        lastWidgetPercent = percent;
        lastWidgetCharging = charging;
    }

    private Notification buildLiveNotification(BatterySnapshot s) {
        Intent open = new Intent(this, BatteryHistoryActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, BatteryMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        double displayedCurrent = s.currentMa();
        String currentLabel = "Current";
        if (Double.isNaN(displayedCurrent)) {
            displayedCurrent = s.averageCurrentMa();
            currentLabel = "Average current";
        }
        String current = Double.isNaN(displayedCurrent)
                ? "unavailable on this device"
                : String.format(Locale.US, "%+.0f mA", displayedCurrent);
        String remaining = s.chargeCounterUah == Long.MIN_VALUE
                ? "unavailable"
                : String.format(Locale.US, "%.0f mAh", s.chargeCounterUah / 1000.0);
        String temp = Float.isNaN(s.temperatureC)
                ? "unavailable"
                : String.format(Locale.US, "%.1f°C", s.temperatureC);

        BatteryRateCalculator.ScreenRates rates = cachedRates;
        boolean screenCurrentlyOn = isScreenOn();
        if (rates == null || cachedRatesCharging != s.isCharging()
                || cachedRatesScreenOn != screenCurrentlyOn) {
            rates = calculateRates(s);
        }
        String screenOnRate = formatPercentRate(rates.screenOnPercentPerHour);
        String screenOff = formatPercentRate(rates.screenOffPercentPerHour);

        String percent = formatPercent(s.percent());
        String title = "Battery " + percent + " · " + BatteryMonitorEngine.statusName(s.status)
                + " · " + temp;
        String collapsed = currentLabel + " " + current + " · Screen on " + screenOnRate
                + " · Screen off " + screenOff;
        String expanded = "Battery: " + percent + " · "
                + BatteryMonitorEngine.statusName(s.status) + " · " + temp
                + "\n" + currentLabel + ": " + current + " · Remaining: " + remaining
                + "\nScreen on: " + screenOnRate
                + "\nScreen off: " + screenOff;

        return new NotificationCompat.Builder(this, CHANNEL_LIVE).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title).setContentText(collapsed)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(expanded)).setContentIntent(content)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW).setShowWhen(false)
                .addAction(0, "Open history", content).addAction(0, "Stop monitor", stopPi).build();
    }

    private void checkAlerts(BatterySnapshot s) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        int chargeAlarm = p.getInt("smart-battery-charge-alarm", 80);
        float tempAlarm = p.getFloat("smart-battery-temp-alarm", 42f);
        boolean chargeLatched = p.getBoolean("smart-battery-charge-alarm-latched", false);
        boolean tempLatched = p.getBoolean("smart-battery-temp-alarm-latched", false);
        if (s.percent() >= 0 && s.isCharging() && s.percent() >= chargeAlarm && !chargeLatched) {
            postAlert("Charge target reached", "Battery reached " + s.percent() + "% (target " + chargeAlarm + "%).");
            p.edit().putBoolean("smart-battery-charge-alarm-latched", true).apply();
        } else if (!s.isCharging() || (s.percent() >= 0
                && s.percent() < Math.max(0, chargeAlarm - 3))) {
            p.edit().putBoolean("smart-battery-charge-alarm-latched", false).apply();
        }
        if (!Float.isNaN(s.temperatureC) && s.temperatureC >= tempAlarm && !tempLatched) {
            postAlert("Battery temperature warning", String.format(Locale.US,
                    "Battery temperature is %.1f°C.", s.temperatureC));
            p.edit().putBoolean("smart-battery-temp-alarm-latched", true).apply();
        } else if (!Float.isNaN(s.temperatureC) && s.temperatureC < tempAlarm - 2f) {
            p.edit().putBoolean("smart-battery-temp-alarm-latched", false).apply();
        }
        if (!Double.isNaN(s.currentMa())) {
            double now = Math.abs(s.currentMa());
            if (!s.isCharging()) {
                double baseline = store.averageDrainMa24h();
                if (!Double.isNaN(baseline) && baseline > 100 && now > baseline * 2.2) {
                    String culprit = BatteryUsageAnalyzer.likelyDrainCause(this, 60L * 60L * 1000L);
                    maybePostRateLimited("drain", "Abnormal battery drain",
                            String.format(Locale.US,
                                    "Current drain %.0f mA is much higher than your 24h baseline %.0f mA. Likely app contributor: %s.",
                                    now, baseline, culprit));
                }
            } else {
                double baseline = store.averageChargeMa24h();
                if (!Double.isNaN(baseline) && baseline > 300 && now < baseline * 0.45) {
                    maybePostRateLimited("slow", "Charging slower than usual",
                            String.format(Locale.US,
                                    "Current charge rate %.0f mA is well below your 24h baseline %.0f mA.",
                                    now, baseline));
                }
            }
        }
    }

    private String formatPercentRate(double value) {
        if (Double.isNaN(value)) return "measuring";
        return String.format(Locale.US, "%+.1f%%/h", value);
    }

    private String formatPercent(int percent) {
        return percent < 0 ? "unavailable" : percent + "%";
    }

    private void maybePostRateLimited(String key, String title, String text) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        String pref = "smart-battery-alert-last-" + key;
        long now = System.currentTimeMillis();
        if (now - p.getLong(pref, 0L) < 3_600_000L) return;
        p.edit().putLong(pref, now).apply();
        postAlert(title, text);
    }

    private void postAlert(String title, String text) {
        NotificationManager nm = notificationManager();
        if (nm == null) return;
        Intent open = new Intent(this, BatteryHistoryActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(ALERT_ID, new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title).setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(content).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH).build());
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = notificationManager();
        if (nm == null) return;
        NotificationChannel live = new NotificationChannel(CHANNEL_LIVE,
                "Battery monitor", NotificationManager.IMPORTANCE_LOW);
        live.setDescription("Battery level, temperature, current and observed screen-on/off rate");
        nm.createNotificationChannel(live);
        NotificationChannel alerts = new NotificationChannel(CHANNEL_ALERTS,
                "Battery alerts", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Charge target, heat, abnormal drain and charging warnings");
        nm.createNotificationChannel(alerts);
    }
}
