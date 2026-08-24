package fr.neamar.kiss;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import fr.neamar.kiss.forwarder.InterfaceTweaks;
import fr.neamar.kiss.index.CommunicationIndexStore;
import fr.neamar.kiss.index.CommunicationIndexer;
import fr.neamar.kiss.loader.LoadAppPojos;

public final class IndexingSettingsActivity extends AppCompatActivity {
    private static final int REQUEST_COMM_PERMISSIONS = 9204;
    private android.content.SharedPreferences prefs;
    private TextView status;
    private TextView retentionLabel;
    private TextView resultLimitLabel;
    private Button rebuild;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        InterfaceTweaks.applySettingsTheme(this, prefs);
        super.onCreate(savedInstanceState);
        CommunicationIndexer.ensureDefaults(this);
        setTitle("Indexing & Search Index");
        setContentView(buildUi());
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(title("INDEX STATUS"));
        status = body(); root.addView(status);

        root.addView(title("CORE LAUNCHER INDEXES"));
        root.addView(toggle("Apps index", "enable-app", true));
        root.addView(toggle("Contacts index", "enable-contacts", true));
        root.addView(toggle("App shortcuts index", "enable-shortcuts", true));
        root.addView(toggle("Index disabled/frozen apps and remembered shortcuts",
                LoadAppPojos.PREF_INDEX_DISABLED_APPS, true));
        TextView disabledNote = body();
        disabledNote.setText("When enabled, Smart S performs an additional installed-package scan including disabled components. Every shortcut Android exposes while an app is available is remembered locally, so known shortcuts remain searchable after that app is frozen or disabled. Apps and shortcuts remain marked disabled until Android reports them enabled again.");
        root.addView(disabledNote);
        root.addView(toggle("Notification history index", "enable-notification-history", true));

        Button rebuildCore = new Button(this);
        rebuildCore.setText("Rebuild apps & shortcuts index now");
        rebuildCore.setOnClickListener(v -> {
            DataHandler dataHandler = KissApplication.getApplication(this).getDataHandler();
            dataHandler.reloadApps();
            dataHandler.reloadShortcuts();
            refreshStatus();
        });
        root.addView(rebuildCore);

        root.addView(title("DEEP COMMUNICATION INDEX"));
        TextView explanation = body();
        explanation.setText("Makes recent calls, SMS text and Truecaller notification content searchable from the normal Smart S Launcher search box. Call/SMS data comes from Android's shared phone providers; Truecaller's private internal database is not accessed.");
        root.addView(explanation);
        root.addView(toggle("Enable communication search index", CommunicationIndexer.PREF_ENABLED, true));
        root.addView(toggle("Index phone call history", CommunicationIndexer.PREF_CALLS, true));
        root.addView(toggle("Index SMS messages", CommunicationIndexer.PREF_SMS, true));
        root.addView(toggle("Index Truecaller notification content", CommunicationIndexer.PREF_TRUECALLER, true));
        root.addView(toggle("Auto-refresh communication index", CommunicationIndexer.PREF_AUTO, true));

        Button permissions = new Button(this);
        permissions.setText("Grant call & message indexing permissions");
        permissions.setOnClickListener(v -> requestCommunicationPermissions());
        root.addView(permissions);

        retentionLabel = body(); root.addView(retentionLabel);
        SeekBar retention = new SeekBar(this);
        retention.setMax(36);
        int days = prefs.getInt(CommunicationIndexer.PREF_DAYS, 365);
        retention.setProgress(daysToStep(days));
        retention.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = stepToDays(progress);
                prefs.edit().putInt(CommunicationIndexer.PREF_DAYS, value).apply();
                retentionLabel.setText("Retention: " + value + " days");
            }
        });
        root.addView(retention);

        resultLimitLabel = body(); root.addView(resultLimitLabel);
        SeekBar limit = new SeekBar(this);
        limit.setMax(195);
        limit.setProgress(Math.max(0, Math.min(195, prefs.getInt(CommunicationIndexer.PREF_LIMIT, 40) - 5)));
        limit.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = 5 + progress;
                prefs.edit().putInt(CommunicationIndexer.PREF_LIMIT, value).apply();
                resultLimitLabel.setText("Maximum communication results per search: " + value);
            }
        });
        root.addView(limit);

        root.addView(title("INDEX MAINTENANCE"));
        rebuild = new Button(this);
        rebuild.setText("Rebuild deep communication index now");
        rebuild.setOnClickListener(v -> rebuildIndex());
        root.addView(rebuild);

        Button clear = new Button(this);
        clear.setText("Clear communication index");
        clear.setOnClickListener(v -> {
            try (CommunicationIndexStore store = new CommunicationIndexStore(this)) { store.clear(); }
            prefs.edit().putLong(CommunicationIndexer.PREF_LAST, 0L).apply();
            refreshStatus();
        });
        root.addView(clear);

        TextView note = body();
        note.setPadding(0, dp(18), 0, 0);
        note.setText("Privacy: indexing is local on the device. Smart S only reads sources you enable and for which Android grants permission. Truecaller calls/SMS are indexed from the Android call/SMS providers, while Truecaller-only in-app data is indexed only when it appears in a notification captured by Smart S.");
        root.addView(note);
        return scroll;
    }

    private Switch toggle(String label, String key, boolean defaultValue) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(15f);
        sw.setPadding(0, dp(6), 0, dp(6));
        sw.setChecked(prefs.getBoolean(key, defaultValue));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).apply();
            if (key.startsWith("smart-index-")) {
                prefs.edit().putLong(CommunicationIndexer.PREF_LAST, 0L).apply();
            }
            if (LoadAppPojos.PREF_INDEX_DISABLED_APPS.equals(key)) {
                DataHandler dataHandler = KissApplication.getApplication(this).getDataHandler();
                dataHandler.reloadApps();
                dataHandler.reloadShortcuts();
            }
        });
        return sw;
    }

    private void requestCommunicationPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS},
                REQUEST_COMM_PERMISSIONS);
    }

    private void rebuildIndex() {
        if (rebuild != null) { rebuild.setEnabled(false); rebuild.setText("Indexing…"); }
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            CommunicationIndexer.rebuild(getApplicationContext());
            runOnUiThread(() -> {
                if (rebuild != null) { rebuild.setEnabled(true); rebuild.setText("Rebuild deep communication index now"); }
                refreshStatus();
            });
        });
    }

    private void refreshStatus() {
        if (status == null) return;
        boolean calls = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
        boolean sms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
        CommunicationIndexStore.Stats s;
        try (CommunicationIndexStore store = new CommunicationIndexStore(this)) { s = store.stats(); }
        long last = prefs.getLong(CommunicationIndexer.PREF_LAST, 0L);
        String lastText = last <= 0 ? "Never" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(last));
        status.setText("Indexed records: " + s.total
                + "\nCalls: " + s.calls + " · SMS: " + s.sms + " · Truecaller notifications: " + s.truecaller
                + "\nDisabled/frozen app indexing: " + (prefs.getBoolean(LoadAppPojos.PREF_INDEX_DISABLED_APPS, true) ? "Enabled" : "Disabled")
                + "\nCall-log permission: " + (calls ? "Granted" : "Not granted")
                + " · SMS permission: " + (sms ? "Granted" : "Not granted")
                + "\nLast deep index: " + lastText);
        if (retentionLabel != null) retentionLabel.setText("Retention: " + prefs.getInt(CommunicationIndexer.PREF_DAYS, 365) + " days");
        if (resultLimitLabel != null) resultLimitLabel.setText("Maximum communication results per search: " + prefs.getInt(CommunicationIndexer.PREF_LIMIT, 40));
    }

    private TextView title(String value) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(16f);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); v.setPadding(0, dp(18), 0, dp(6));
        return v;
    }

    private TextView body() {
        TextView v = new TextView(this); v.setTextSize(14.5f); v.setLineSpacing(0f, 1.15f); v.setGravity(Gravity.START); return v;
    }

    private int daysToStep(int days) {
        if (days <= 30) return 0;
        if (days <= 90) return 1;
        if (days <= 180) return 2;
        if (days <= 365) return 3;
        return Math.min(36, 3 + (days - 365) / 90);
    }

    private int stepToDays(int step) {
        if (step <= 0) return 30;
        if (step == 1) return 90;
        if (step == 2) return 180;
        if (step == 3) return 365;
        return Math.min(3650, 365 + (step - 3) * 90);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
