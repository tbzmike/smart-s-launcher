package fr.neamar.kiss.battery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

/**
 * Persistent battery monitor with power-aware sampling.
 *
 * Hardware reads stay lightweight, while expensive DB analytics, usage attribution and widgets are
 * intentionally throttled. This avoids the monitor becoming one of the phone's largest drains.
 */
public final class BatteryMonitorService extends Service {
    public static final String ACTION_START = "fr.neamar.kiss.battery.START";
    public static final String ACTION_STOP = "fr.neamar.kiss.battery.STOP";
    private static final String CHANNEL_LIVE = "smart_battery_live";
    private static final String CHANNEL_ALERTS = "smart_battery_alerts";
    private static final int LIVE_ID = 8450;
    private static final int ALERT_ID = 8451;

    private static final long SAMPLE_CHARGING_MS = 2L * 60_000L;
    private static final long SAMPLE_SCREEN_ON_MS = 5L * 60_000L;
    private static final long SAMPLE_SCREEN_OFF_MS = 15L * 60_000L;
    private static final long SAMPLE_LOW_BATTERY_SCREEN_OFF_MS = 10L * 60_000L;
    private static final long ANALYTICS_CACHE_MS = 10L * 60_000L;
    private static final long WIDGET_MIN_REFRESH_MS = 10L * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BatteryHistoryStore store;
    private Analytics analytics;
    private long lastWidgetRefreshMs;
    private int lastWidgetPercent = -1;
    private boolean lastWidgetCharging;

    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            BatterySnapshot s = sampleNow();
            handler.postDelayed(this, nextInterval(s));
        }
    };

    private static final class Analytics {
        long timestamp;
        boolean charging;
        String capacity = "learning";
        String healthText = "learning";
        int design;
        String screenOnMa = "learning";
        String screenOffMa = "learning";
        String overallSpeed = "learning";
        String screenOnSpeed = "learning";
        String screenOffSpeed = "learning";
        String sessionAverage = "learning";
        String remaining = "learning";
        String likelyCause = "learning";
    }

    @Override public void onCreate() {
        super.onCreate();
        store = new BatteryHistoryStore(this);
        createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean("smart-battery-monitor-enabled", false).apply();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean("smart-battery-monitor-enabled", true).apply();
        BatterySnapshot initial = BatteryMonitorEngine.read(this);
        startForeground(LIVE_ID, buildLiveNotification(initial));
        handler.removeCallbacks(sampler);
        handler.post(sampler);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (store != null) store.close();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private NotificationManager notificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private long nextInterval(BatterySnapshot s) {
        if (s.isCharging()) return SAMPLE_CHARGING_MS;
        if (isScreenOn()) return SAMPLE_SCREEN_ON_MS;
        return s.percent() <= 15 ? SAMPLE_LOW_BATTERY_SCREEN_OFF_MS : SAMPLE_SCREEN_OFF_MS;
    }

    private BatterySnapshot sampleNow() {
        BatterySnapshot s = BatteryMonitorEngine.read(this);
        store.add(s);

        NotificationManager nm = notificationManager();
        if (nm != null) nm.notify(LIVE_ID, buildLiveNotification(s));

        maybeRefreshWidgets(s);
        checkAlerts(s);
        return s;
    }

    private void maybeRefreshWidgets(BatterySnapshot s) {
        long now = System.currentTimeMillis();
        boolean meaningfulStateChange = s.percent() != lastWidgetPercent || s.isCharging() != lastWidgetCharging;
        if (!meaningfulStateChange && now - lastWidgetRefreshMs < WIDGET_MIN_REFRESH_MS) return;
        BatteryWidgetProvider.updateAll(this);
        lastWidgetRefreshMs = now;
        lastWidgetPercent = s.percent();
        lastWidgetCharging = s.isCharging();
    }

    private Analytics analyticsFor(BatterySnapshot s) {
        long now = System.currentTimeMillis();
        if (analytics != null && analytics.charging == s.isCharging()
                && now - analytics.timestamp < ANALYTICS_CACHE_MS) return analytics;

        Analytics a = new Analytics();
        a.timestamp = now;
        a.charging = s.isCharging();

        long estimated = store.estimatedFullCapacityUah();
        a.design = BatteryCapacityEstimator.designCapacityMah(this);
        double health = BatteryCapacityEstimator.healthPercent(this, estimated);
        a.capacity = estimated > 0 ? String.format(Locale.US, "%.0f mAh", estimated / 1000.0) : "learning";
        a.healthText = Double.isNaN(health) ? "learning" : String.format(Locale.US, "%.1f%%", health);
        a.screenOnMa = formatMa(store.averageScreenOnDrainMa24h());
        a.screenOffMa = formatMa(store.averageScreenOffDrainMa24h());

        BatteryHistoryStore.CurrentSessionStats session = store.currentSessionStats(s);
        a.overallSpeed = formatPercentRate(session.percentPerHour, s.isCharging());
        a.screenOnSpeed = formatPercentRate(session.screenOnPercentPerHour, s.isCharging());
        a.screenOffSpeed = formatPercentRate(session.screenOffPercentPerHour, s.isCharging());
        a.sessionAverage = formatSignedMa(session.averageCurrentMa);
        a.remaining = session.estimatedRemainingMs == Long.MIN_VALUE
                ? "learning" : formatDuration(session.estimatedRemainingMs);
        a.likelyCause = s.isCharging() ? "" : BatteryUsageAnalyzer.likelyDrainCause(this, 60L * 60L * 1000L);

        analytics = a;
        return a;
    }

    private Notification buildLiveNotification(BatterySnapshot s) {
        Intent open = new Intent(this, BatteryHistoryActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, BatteryMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String current = Double.isNaN(s.currentMa()) ? "current unavailable"
                : String.format(Locale.US, "%.0f mA", Math.abs(s.currentMa()));
        String power = Double.isNaN(s.powerW()) ? "— W" : String.format(Locale.US, "%.2f W", s.powerW());
        String temp = Float.isNaN(s.temperatureC) ? "temp unavailable" : String.format(Locale.US, "%.1f°C", s.temperatureC);
        String voltage = s.voltageMv > 0 ? s.voltageMv + " mV" : "voltage unavailable";
        String state = s.isCharging() ? "Charging" : "Discharging";
        String source = BatteryMonitorEngine.sourceName(s.plugged);
        String timeToFull = s.chargeTimeRemainingMs == Long.MIN_VALUE ? "—" : formatDuration(s.chargeTimeRemainingMs);
        Analytics a = analyticsFor(s);

        String collapsed = s.isCharging()
                ? current + " · Charge " + a.overallSpeed + " · " + temp
                : current + " · Drain " + a.overallSpeed + " · " + temp;

        StringBuilder expanded = new StringBuilder();
        expanded.append(current).append(" · ").append(power).append(" · ").append(temp).append(" · ").append(voltage)
                .append("\n").append(s.isCharging() ? "Overall charge speed: " : "Overall drain speed: ").append(a.overallSpeed)
                .append(" · session avg: ").append(a.sessionAverage).append(" · remaining: ").append(a.remaining)
                .append("\nScreen-on ").append(s.isCharging() ? "charge" : "drain").append(" speed: ").append(a.screenOnSpeed)
                .append(" · Screen-off ").append(s.isCharging() ? "charge" : "drain").append(" speed: ").append(a.screenOffSpeed)
                .append("\nSource: ").append(source);
        if (s.isCharging()) expanded.append(" · time to full: ").append(timeToFull);
        expanded.append("\n24h current — screen on: ").append(a.screenOnMa).append(" · screen off: ").append(a.screenOffMa);
        if (!s.isCharging()) expanded.append("\nLikely app contributor: ").append(a.likelyCause);
        expanded.append("\nCapacity: ").append(a.capacity);
        if (a.design > 0) expanded.append(" / ").append(a.design).append(" mAh design");
        expanded.append(" · estimated health: ").append(a.healthText)
                .append("\nPower-aware sampling: 2m charging · 5m screen-on · 15m screen-off")
                .append("\nTap for Daily / Weekly / Monthly history, sessions, wear and reports.");

        return new NotificationCompat.Builder(this, CHANNEL_LIVE)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Battery " + s.percent() + "% · " + state)
                .setContentText(collapsed)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(expanded.toString()))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .addAction(0, "Open history", content)
                .addAction(0, "Stop monitor", stopPi)
                .build();
    }

    private void checkAlerts(BatterySnapshot s) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        int chargeAlarm = p.getInt("smart-battery-charge-alarm", 80);
        float tempAlarm = p.getFloat("smart-battery-temp-alarm", 42f);
        boolean chargeLatched = p.getBoolean("smart-battery-charge-alarm-latched", false);
        boolean tempLatched = p.getBoolean("smart-battery-temp-alarm-latched", false);

        if (s.isCharging() && s.percent() >= chargeAlarm && !chargeLatched) {
            postAlert("Charge target reached", "Battery reached " + s.percent() + "% (target " + chargeAlarm + "%).");
            p.edit().putBoolean("smart-battery-charge-alarm-latched", true).apply();
        } else if (!s.isCharging() || s.percent() < Math.max(0, chargeAlarm - 3)) {
            p.edit().putBoolean("smart-battery-charge-alarm-latched", false).apply();
        }

        if (!Float.isNaN(s.temperatureC) && s.temperatureC >= tempAlarm && !tempLatched) {
            postAlert("Battery temperature warning", String.format(Locale.US, "Battery temperature is %.1f°C.", s.temperatureC));
            p.edit().putBoolean("smart-battery-temp-alarm-latched", true).apply();
        } else if (!Float.isNaN(s.temperatureC) && s.temperatureC < tempAlarm - 2f) {
            p.edit().putBoolean("smart-battery-temp-alarm-latched", false).apply();
        }

        if (!Double.isNaN(s.currentMa())) {
            double now = Math.abs(s.currentMa());
            if (!s.isCharging()) {
                double baseline = store.averageDrainMa24h();
                if (!Double.isNaN(baseline) && baseline > 100 && now > baseline * 2.2) {
                    maybePostRateLimited("drain", "Abnormal battery drain",
                            String.format(Locale.US,
                                    "Current drain %.0f mA is much higher than your 24h baseline %.0f mA.",
                                    now, baseline));
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

    private String formatMa(double value) {
        return Double.isNaN(value) ? "learning" : String.format(Locale.US, "%.0f mA", value);
    }

    private String formatSignedMa(double value) {
        return Double.isNaN(value) ? "learning" : String.format(Locale.US, "%+.0f mA", value);
    }

    private String formatPercentRate(double value, boolean charging) {
        if (Double.isNaN(value)) return "learning";
        double normalized = charging ? Math.abs(value) : -Math.abs(value);
        return String.format(Locale.US, "%+.1f%%/h", normalized);
    }

    private String formatDuration(long ms) {
        long minutes = Math.max(0L, ms / 60_000L);
        return (minutes / 60) + "h " + (minutes % 60) + "m";
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
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build());
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = notificationManager();
        if (nm == null) return;
        NotificationChannel live = new NotificationChannel(CHANNEL_LIVE, "Battery monitor",
                NotificationManager.IMPORTANCE_LOW);
        live.setDescription("Live battery usage, drain speed, charging rate, health and temperature");
        nm.createNotificationChannel(live);
        NotificationChannel alerts = new NotificationChannel(CHANNEL_ALERTS, "Battery alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Charge target, heat, abnormal drain and charging warnings");
        nm.createNotificationChannel(alerts);
    }
}
