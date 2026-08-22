package fr.neamar.kiss;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import fr.neamar.kiss.battery.BatteryCapacityEstimator;
import fr.neamar.kiss.battery.BatteryHistoryGraphView;
import fr.neamar.kiss.battery.BatteryHistoryStore;
import fr.neamar.kiss.battery.BatteryMonitorEngine;
import fr.neamar.kiss.battery.BatteryMonitorService;
import fr.neamar.kiss.battery.BatteryMonitorStarter;
import fr.neamar.kiss.battery.BatterySnapshot;
import fr.neamar.kiss.battery.BatteryUsageAnalyzer;
import fr.neamar.kiss.forwarder.InterfaceTweaks;

public final class BatteryMonitorActivity extends AppCompatActivity {
    private static final long DAY_MS = 86_400_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView live;
    private TextView health;
    private TextView history;
    private TextView sessions;
    private TextView wear;
    private TextView reports;
    private TextView appActivity;
    private TextView chargeAlarmLabel;
    private TextView tempAlarmLabel;
    private BatteryHistoryGraphView graph;
    private BatteryHistoryStore store;
    private SharedPreferences prefs;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateDashboard();
            handler.postDelayed(this, 2000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        InterfaceTweaks.applySettingsTheme(this, prefs);
        super.onCreate(savedInstanceState);
        store = new BatteryHistoryStore(this);
        setTitle("Smart S Battery Monitor");
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
        BatteryMonitorStarter.ensureRunning(this);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (store != null) store.close();
        super.onDestroy();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(sectionTitle("LIVE BATTERY"));
        live = bodyText(); root.addView(live);

        root.addView(sectionTitle("24-HOUR BATTERY & TEMPERATURE GRAPH"));
        graph = new BatteryHistoryGraphView(this);
        graph.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(graph, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));
        TextView legend = bodyText();
        legend.setText("Blue: battery level · Orange: temperature trend");
        root.addView(legend);

        root.addView(sectionTitle("HEALTH & CAPACITY"));
        health = bodyText(); root.addView(health);

        root.addView(sectionTitle("USAGE & HISTORY"));
        history = bodyText(); root.addView(history);

        root.addView(sectionTitle("CHARGING / DISCHARGING SESSIONS"));
        sessions = bodyText(); root.addView(sessions);

        root.addView(sectionTitle("BATTERY WEAR & CHARGE HABITS"));
        wear = bodyText(); root.addView(wear);

        root.addView(sectionTitle("DAILY / WEEKLY REPORT"));
        reports = bodyText(); root.addView(reports);

        root.addView(sectionTitle("APP ACTIVITY CORRELATION"));
        appActivity = bodyText(); root.addView(appActivity);
        Button usageAccess = new Button(this);
        usageAccess.setText("Open usage access settings");
        usageAccess.setOnClickListener(v -> startActivity(BatteryUsageAnalyzer.usageAccessIntent()));
        root.addView(usageAccess);
        TextView usageNote = bodyText();
        usageNote.setText("App activity shows foreground time only. Android does not expose trustworthy per-app battery-current attribution to ordinary apps, so Smart S does not invent per-app mAh values.");
        root.addView(usageNote);

        root.addView(sectionTitle("CHARGE TARGET ALARM"));
        chargeAlarmLabel = bodyText(); root.addView(chargeAlarmLabel);
        SeekBar charge = new SeekBar(this);
        charge.setMax(50);
        charge.setProgress(Math.max(0, Math.min(50, prefs.getInt("smart-battery-charge-alarm", 80) - 50)));
        charge.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = 50 + progress;
                prefs.edit().putInt("smart-battery-charge-alarm", value).apply();
                chargeAlarmLabel.setText("Alert at " + value + "%");
            }
        });
        root.addView(charge);

        root.addView(sectionTitle("TEMPERATURE ALARM"));
        tempAlarmLabel = bodyText(); root.addView(tempAlarmLabel);
        SeekBar temp = new SeekBar(this);
        temp.setMax(25);
        temp.setProgress(Math.max(0, Math.min(25, Math.round(prefs.getFloat("smart-battery-temp-alarm", 42f) - 30f))));
        temp.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = 30f + progress;
                prefs.edit().putFloat("smart-battery-temp-alarm", value).apply();
                tempAlarmLabel.setText(String.format(Locale.US, "Alert at %.0f°C", value));
            }
        });
        root.addView(temp);

        Button start = new Button(this);
        start.setText("Enable live notification monitor");
        start.setOnClickListener(v -> {
            prefs.edit().putBoolean("smart-battery-monitor-enabled", true).apply();
            requestNotificationPermissionIfNeeded();
            BatteryMonitorStarter.ensureRunning(this);
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop background monitor");
        stop.setOnClickListener(v -> startService(new Intent(this, BatteryMonitorService.class)
                .setAction(BatteryMonitorService.ACTION_STOP)));
        root.addView(stop);

        TextView note = bodyText();
        note.setPadding(0, dp(18), 0, 0);
        note.setText("Measured values come from Android/device hardware. Capacity, health trends, screen-on/off drain, charge-cycle equivalents and anomaly baselines are learned from stored samples. Unsupported hardware values are shown as unavailable rather than invented.");
        root.addView(note);
        return scroll;
    }

    private void updateDashboard() {
        BatterySnapshot s = BatteryMonitorEngine.read(this);
        String current = Double.isNaN(s.currentMa()) ? "Unavailable" : String.format(Locale.US, "%.0f mA", s.currentMa());
        String avg = Double.isNaN(s.averageCurrentMa()) ? "Unavailable" : String.format(Locale.US, "%.0f mA", s.averageCurrentMa());
        String power = Double.isNaN(s.powerW()) ? "Unavailable" : String.format(Locale.US, "%.2f W", s.powerW());
        String time = s.chargeTimeRemainingMs == Long.MIN_VALUE ? "Unavailable" : formatDuration(s.chargeTimeRemainingMs);
        String temperature = Float.isNaN(s.temperatureC) ? "Unavailable" : String.format(Locale.US, "%.1f°C", s.temperatureC);
        live.setText("Battery: " + s.percent() + "%\nState: " + (s.isCharging() ? "Charging" : "Discharging")
                + " via " + BatteryMonitorEngine.sourceName(s.plugged)
                + "\nCurrent now: " + current + "\nAverage current: " + avg + "\nPower: " + power
                + "\nTemperature: " + temperature
                + "\nVoltage: " + (s.voltageMv > 0 ? s.voltageMv + " mV" : "Unavailable") + "\nTime to full: " + time);

        long estimated = store.estimatedFullCapacityUah();
        int design = BatteryCapacityEstimator.designCapacityMah(this);
        double healthPercent = BatteryCapacityEstimator.healthPercent(this, estimated);
        String cap = estimated > 0 ? String.format(Locale.US, "%.0f mAh", estimated / 1000.0) : "Learning — needs charging ranges of at least 15%";
        String counter = s.chargeCounterUah == Long.MIN_VALUE ? "Unavailable" : String.format(Locale.US, "%.0f mAh", s.chargeCounterUah / 1000.0);
        String energy = s.energyNwh == Long.MIN_VALUE ? "Unavailable" : String.format(Locale.US, "%.2f Wh", s.energyNwh / 1_000_000.0);
        String cycles = s.cycleCount < 0 ? "Unavailable on this device" : Integer.toString(s.cycleCount);
        health.setText("Android health: " + BatteryMonitorEngine.healthName(s.health)
                + "\nDesign capacity: " + (design > 0 ? design + " mAh" : "Unavailable")
                + "\nEstimated full capacity: " + cap
                + "\nEstimated health: " + (Double.isNaN(healthPercent) ? "Learning…" : String.format(Locale.US, "%.1f%%", healthPercent))
                + "\nCharge counter: " + counter + "\nEnergy counter: " + energy
                + "\nHardware cycle count: " + cycles);

        double avgDrain = store.averageDrainMa24h();
        double screenOn = store.averageScreenOnDrainMa24h();
        double screenOff = store.averageScreenOffDrainMa24h();
        double charging = store.averageChargeMa24h();
        double avgTemp = store.averageTemperature24h();
        history.setText("Stored samples: " + store.sampleCount()
                + "\n24h average discharge: " + formatMa(avgDrain)
                + "\nScreen-on drain: " + formatMa(screenOn)
                + "\nScreen-off drain: " + formatMa(screenOff)
                + "\nCharging-rate baseline: " + formatMa(charging)
                + "\n24h average temperature: " + (Double.isNaN(avgTemp) ? "Learning…" : String.format(Locale.US, "%.1f°C", avgTemp))
                + "\nSmart alerts: high heat · high drain · slow charging · charge target"
                + "\nHistory retention: 180 days · adaptive sampling: 1 min charging / 3 min discharging");

        graph.setPoints(store.recentSamples(DAY_MS, 500));
        sessions.setText(formatSessions(store.recentSessions(8)));
        double cycles30 = store.equivalentChargeCycles(30L * DAY_MS);
        double highSoc30 = store.highSocChargePercent(30L * DAY_MS);
        wear.setText(String.format(Locale.US,
                "30-day equivalent charge cycles: %.2f\nCharge added above 80%%: %.0f percentage-points\nHabit note: lower high-state-of-charge time and lower heat generally reduce lithium-ion aging.\nThese are charge-habit indicators, not laboratory cycle-life measurements.",
                cycles30, highSoc30));

        double drain7 = store.averageDrainMa7d();
        reports.setText("Today\n• average drain: " + formatMa(avgDrain)
                + "\n• screen-on: " + formatMa(screenOn) + " · screen-off: " + formatMa(screenOff)
                + "\n• average temperature: " + (Double.isNaN(avgTemp) ? "Learning…" : String.format(Locale.US, "%.1f°C", avgTemp))
                + "\n\n7-day trend\n• average discharge: " + formatMa(drain7)
                + "\n\n30-day charging\n• equivalent cycles: " + String.format(Locale.US, "%.2f", cycles30));

        appActivity.setText(BatteryUsageAnalyzer.topForegroundApps24h(this, 6));
        chargeAlarmLabel.setText("Alert at " + prefs.getInt("smart-battery-charge-alarm", 80) + "%");
        tempAlarmLabel.setText(String.format(Locale.US, "Alert at %.0f°C", prefs.getFloat("smart-battery-temp-alarm", 42f)));
    }

    private String formatSessions(List<BatteryHistoryStore.SessionSummary> list) {
        if (list.isEmpty()) return "Learning sessions… Keep the monitor enabled while charging and discharging.";
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d HH:mm", Locale.getDefault());
        StringBuilder out = new StringBuilder();
        for (int i = list.size() - 1; i >= 0; i--) {
            BatteryHistoryStore.SessionSummary s = list.get(i);
            if (out.length() > 0) out.append("\n\n");
            out.append(s.charging ? "Charge" : "Discharge").append(" · ")
                    .append(fmt.format(new Date(s.startMs))).append(" → ")
                    .append(fmt.format(new Date(s.endMs))).append('\n')
                    .append(s.startLevel).append("% → ").append(s.endLevel).append("% · ")
                    .append(formatDuration(s.durationMs()));
            if (!Double.isNaN(s.averageCurrentMa)) out.append(" · avg ").append(String.format(Locale.US, "%.0f mA", s.averageCurrentMa));
            if (!Float.isNaN(s.maxTemperatureC)) out.append(" · max ").append(String.format(Locale.US, "%.1f°C", s.maxTemperatureC));
            if (s.deliveredUah > 0) out.append(" · ~").append(String.format(Locale.US, "%.0f mAh", s.deliveredUah / 1000.0));
        }
        return out.toString();
    }

    private String formatMa(double value) {
        return Double.isNaN(value) ? "Learning…" : String.format(Locale.US, "%.0f mA", value);
    }

    private TextView sectionTitle(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setTextSize(16f);
        v.setPadding(0, dp(18), 0, dp(6));
        return v;
    }

    private TextView bodyText() {
        TextView v = new TextView(this);
        v.setTextSize(15f);
        v.setLineSpacing(0f, 1.18f);
        v.setGravity(Gravity.START);
        return v;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 8450);
        }
    }

    private String formatDuration(long ms) {
        long minutes = Math.max(0, ms / 60_000L);
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
