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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.util.Locale;

import fr.neamar.kiss.BatteryMonitorActivity;
import fr.neamar.kiss.R;

public final class BatteryMonitorService extends Service {
    public static final String ACTION_START = "fr.neamar.kiss.battery.START";
    public static final String ACTION_STOP = "fr.neamar.kiss.battery.STOP";
    private static final String CHANNEL_LIVE = "smart_battery_live";
    private static final String CHANNEL_ALERTS = "smart_battery_alerts";
    private static final int LIVE_ID = 8450;
    private static final int ALERT_ID = 8451;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BatteryHistoryStore store;

    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            sampleNow();
            BatterySnapshot s = BatteryMonitorEngine.read(BatteryMonitorService.this);
            long interval = s.isCharging() ? 60_000L : 180_000L;
            handler.postDelayed(this, interval);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        store = new BatteryHistoryStore(this);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean("smart-battery-monitor-enabled", false).apply();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean("smart-battery-monitor-enabled", true).apply();
        startForeground(LIVE_ID, buildLiveNotification(BatteryMonitorEngine.read(this)));
        handler.removeCallbacks(sampler);
        handler.post(sampler);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (store != null) store.close();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private NotificationManager notificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private void sampleNow() {
        BatterySnapshot s = BatteryMonitorEngine.read(this);
        store.add(s);
        NotificationManager nm = notificationManager();
        if (nm != null) nm.notify(LIVE_ID, buildLiveNotification(s));
        BatteryWidgetProvider.updateAll(this);
        checkAlerts(s);
    }

    private Notification buildLiveNotification(BatterySnapshot s) {
        Intent open = new Intent(this, BatteryMonitorActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, BatteryMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String current = Double.isNaN(s.currentMa()) ? "current unavailable"
                : String.format(Locale.US, "%.0f mA", Math.abs(s.currentMa()));
        String power = Double.isNaN(s.powerW()) ? ""
                : String.format(Locale.US, " · %.2f W", s.powerW());
        String text = current + power + " · " + String.format(Locale.US, "%.1f°C", s.temperatureC)
                + " · " + s.voltageMv + " mV";
        String state = s.isCharging() ? "Charging" : "Discharging";

        return new NotificationCompat.Builder(this, CHANNEL_LIVE)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Battery " + s.percent() + "% · " + state)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text + "\nTap for health, history, capacity and charging details."))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
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
                            String.format(Locale.US, "Current drain %.0f mA is much higher than your 24h baseline %.0f mA.", now, baseline));
                }
            } else {
                double baseline = store.averageChargeMa24h();
                if (!Double.isNaN(baseline) && baseline > 300 && now < baseline * 0.45) {
                    maybePostRateLimited("slow", "Charging slower than usual",
                            String.format(Locale.US, "Current charge rate %.0f mA is well below your 24h baseline %.0f mA.", now, baseline));
                }
            }
        }
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
        nm.notify(ALERT_ID, new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
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
        live.setDescription("Live battery usage, charging rate and temperature");
        nm.createNotificationChannel(live);
        NotificationChannel alerts = new NotificationChannel(CHANNEL_ALERTS, "Battery alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Charge target, heat and abnormal battery warnings");
        nm.createNotificationChannel(alerts);
    }
}
