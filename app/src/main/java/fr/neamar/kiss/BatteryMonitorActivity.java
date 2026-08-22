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

import java.util.Locale;

import fr.neamar.kiss.battery.BatteryHistoryStore;
import fr.neamar.kiss.battery.BatteryMonitorEngine;
import fr.neamar.kiss.battery.BatteryMonitorService;
import fr.neamar.kiss.battery.BatteryMonitorStarter;
import fr.neamar.kiss.battery.BatterySnapshot;
import fr.neamar.kiss.forwarder.InterfaceTweaks;

public final class BatteryMonitorActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView live;
    private TextView health;
    private TextView history;
    private TextView chargeAlarmLabel;
    private TextView tempAlarmLabel;
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
        root.addView(sectionTitle("HEALTH & CAPACITY"));
        health = bodyText(); root.addView(health);
        root.addView(sectionTitle("USAGE & HISTORY"));
        history = bodyText(); root.addView(history);

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
        temp.setProgress(Math.round(prefs.getFloat("smart-battery-temp-alarm", 42f) - 30f));
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
            BatteryMonitorStarter.ensureRunning(this);
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop background monitor");
        stop.setOnClickListener(v -> startService(new Intent(this, BatteryMonitorService.class)
                .setAction(BatteryMonitorService.ACTION_STOP)));
        root.addView(stop);

        TextView note = bodyText();
        note.setText("Measured values come from Android/device hardware. Capacity, health trends and usage baselines are learned from stored charging/discharging samples; unsupported hardware values are shown as unavailable rather than invented.");
        root.addView(note);
        return scroll;
    }

    private void updateDashboard() {
        BatterySnapshot s = BatteryMonitorEngine.read(this);
        String current = Double.isNaN(s.currentMa()) ? "Unavailable" : String.format(Locale.US, "%.0f mA", s.currentMa());
        String avg = Double.isNaN(s.averageCurrentMa()) ? "Unavailable" : String.format(Locale.US, "%.0f mA", s.averageCurrentMa());
        String power = Double.isNaN(s.powerW()) ? "Unavailable" : String.format(Locale.US, "%.2f W", s.powerW());
        String time = s.chargeTimeRemainingMs == Long.MIN_VALUE ? "Unavailable" : formatDuration(s.chargeTimeRemainingMs);
        live.setText("Battery: " + s.percent() + "%\nState: " + (s.isCharging() ? "Charging" : "Discharging")
                + " via " + BatteryMonitorEngine.sourceName(s.plugged)
                + "\nCurrent now: " + current + "\nAverage current: " + avg + "\nPower: " + power
                + "\nTemperature: " + String.format(Locale.US, "%.1f°C", s.temperatureC)
                + "\nVoltage: " + s.voltageMv + " mV\nTime to full: " + time);

        long estimated = store.estimatedFullCapacityUah();
        String cap = estimated > 0 ? String.format(Locale.US, "%.0f mAh", estimated / 1000.0) : "Learning — needs charging ranges of at least 15%";
        String counter = s.chargeCounterUah == Long.MIN_VALUE ? "Unavailable" : String.format(Locale.US, "%.0f mAh", s.chargeCounterUah / 1000.0);
        String energy = s.energyNwh == Long.MIN_VALUE ? "Unavailable" : String.format(Locale.US, "%.2f Wh", s.energyNwh / 1_000_000.0);
        String cycles = s.cycleCount < 0 ? "Unavailable on this device" : Integer.toString(s.cycleCount);
        health.setText("Android health: " + BatteryMonitorEngine.healthName(s.health)
                + "\nCharge counter: " + counter + "\nEnergy counter: " + energy
                + "\nEstimated full capacity: " + cap + "\nCycle count: " + cycles);

        double avgDrain = store.averageDrainMa24h();
        double avgTemp = store.averageTemperature24h();
        history.setText("Stored samples: " + store.sampleCount()
                + "\n24h average discharge current: " + (Double.isNaN(avgDrain) ? "Learning…" : String.format(Locale.US, "%.0f mA", avgDrain))
                + "\n24h average temperature: " + (Double.isNaN(avgTemp) ? "Learning…" : String.format(Locale.US, "%.1f°C", avgTemp))
                + "\nHistory retention: 120 days\nAdaptive sampling: 1 min charging / 3 min discharging");
        chargeAlarmLabel.setText("Alert at " + prefs.getInt("smart-battery-charge-alarm", 80) + "%");
        tempAlarmLabel.setText(String.format(Locale.US, "Alert at %.0f°C", prefs.getFloat("smart-battery-temp-alarm", 42f)));
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
