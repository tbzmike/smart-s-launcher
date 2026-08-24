package fr.neamar.kiss;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import fr.neamar.kiss.appusage.AppUsageStore;
import fr.neamar.kiss.appusage.AppUsageTracker;

/** Tree-style, local timeline of phone/app usage retained for up to 365 days. */
public final class AppUsageActivity extends AppCompatActivity {
    private static final String KIND_PHONE_DAILY = "PHONE_DAILY";

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "smart-s-app-usage-ui"));
    private final AtomicInteger loadGeneration = new AtomicInteger();

    private TextView status;
    private TextView summary;
    private Button grantAccess;
    private ProgressBar progress;
    private Spinner rangeSpinner;
    private TimelineAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("App usage");
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        loadGeneration.incrementAndGet();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("Phone usage timeline");
        title.setTextSize(22f);
        title.setGravity(Gravity.START);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("Smart S imports Android usage events, screen state and package history, then keeps the imported local timeline for up to 365 days. Detailed Android events themselves are only retained by Android for a limited time.");
        note.setTextSize(13f);
        note.setPadding(0, dp(4), 0, dp(8));
        root.addView(note);

        status = new TextView(this);
        status.setTextSize(14f);
        status.setPadding(0, 0, 0, dp(6));
        root.addView(status);

        grantAccess = new Button(this);
        grantAccess.setText("Grant Usage Access");
        grantAccess.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        root.addView(grantAccess, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        TextView rangeLabel = new TextView(this);
        rangeLabel.setText("Show:  ");
        controls.addView(rangeLabel);

        rangeSpinner = new Spinner(this);
        ArrayAdapter<String> rangeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Today", "7 days", "30 days", "365 days"});
        rangeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rangeSpinner.setAdapter(rangeAdapter);
        rangeSpinner.setSelection(1);
        controls.addView(rangeSpinner, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> reload());
        controls.addView(refresh);
        root.addView(controls);

        summary = new TextView(this);
        summary.setTextSize(14f);
        summary.setPadding(0, dp(6), 0, dp(8));
        root.addView(summary);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setHasFixedSize(false);
        adapter = new TimelineAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView sourceNote = new TextView(this);
        sourceNote.setText("Install source: Smart S records Android's installer/source package and source type. Android does not expose another app's original APK/download URL; a known store page is shown when it can be determined without guessing.");
        sourceNote.setTextSize(11f);
        sourceNote.setPadding(0, dp(6), 0, 0);
        root.addView(sourceNote);

        rangeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                reload();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        setContentView(root);
    }

    private void reload() {
        final int generation = loadGeneration.incrementAndGet();
        final boolean enabled = AppUsageTracker.isEnabled(this);
        final boolean access = AppUsageTracker.hasUsageAccess(this);
        grantAccess.setVisibility(access ? View.GONE : View.VISIBLE);
        status.setText(enabled
                ? (access ? "Tracking ON · syncing Android usage into the local 365-day store"
                          : "Tracking ON · Usage Access is required for app/screen history")
                : "Tracking OFF · existing local history remains viewable");
        progress.setVisibility(View.VISIBLE);

        final int days = selectedDays();
        executor.execute(() -> {
            if (enabled && access) AppUsageTracker.syncNow(getApplicationContext());
            long now = System.currentTimeMillis();
            long since = days == 1 ? AppUsageStore.startOfDay(now)
                    : now - days * 24L * 60L * 60L * 1000L;
            AppUsageStore store = AppUsageStore.get(getApplicationContext());
            List<AppUsageStore.TimelineEntry> combined = new ArrayList<>();
            combined.addAll(store.getTimeline(since, 4500));
            combined.addAll(store.getDailyUsageTimeline(since, 2500));
            for (AppUsageStore.DailyPhoneState phone : store.getDailyPhoneStates(since, 366)) {
                combined.add(new AppUsageStore.TimelineEntry(
                        "daily-phone:" + phone.dayMs,
                        phone.dayMs + 23L * 60L * 60L * 1000L + 59L * 60L * 1000L,
                        0L,
                        KIND_PHONE_DAILY,
                        null,
                        null,
                        phone.screenOnMs,
                        false,
                        phone.screenOffMs + "|" + phone.unlockCount,
                        null,
                        null));
            }
            combined.sort(Comparator.comparingLong((AppUsageStore.TimelineEntry e) -> e.startMs)
                    .reversed());
            if (combined.size() > 5000) {
                combined = new ArrayList<>(combined.subList(0, 5000));
            }
            AppUsageStore.Summary totals = store.getSummary(since, now);
            List<Row> rows = buildRows(combined);
            runOnUiThread(() -> {
                if (generation != loadGeneration.get() || isFinishing()) return;
                progress.setVisibility(View.GONE);
                summary.setText("Apps used: " + totals.appsUsed
                        + "   •   App time: " + duration(totals.appUsageMs)
                        + "   •   Unlocks: " + totals.unlockCount
                        + "\nScreen on: " + duration(totals.screenOnMs)
                        + "   •   Screen off: " + duration(totals.screenOffMs));
                adapter.setRows(rows);
            });
        });
    }

    private int selectedDays() {
        if (rangeSpinner == null) return 7;
        switch (rangeSpinner.getSelectedItemPosition()) {
            case 0:
                return 1;
            case 1:
                return 7;
            case 2:
                return 30;
            default:
                return 365;
        }
    }

    private List<Row> buildRows(List<AppUsageStore.TimelineEntry> entries) {
        if (entries.isEmpty()) {
            return Collections.singletonList(new Row(false,
                    "No usage events are stored for this period yet."));
        }
        List<Row> rows = new ArrayList<>();
        SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String previousDay = null;
        for (AppUsageStore.TimelineEntry entry : entries) {
            String day = dayFormat.format(new Date(entry.startMs));
            if (!TextUtils.equals(day, previousDay)) {
                rows.add(new Row(true, day));
                previousDay = day;
            }
            rows.add(new Row(false, formatEntry(entry, timeFormat)));
        }
        return rows;
    }

    private String formatEntry(AppUsageStore.TimelineEntry e, SimpleDateFormat timeFormat) {
        String time = timeFormat.format(new Date(e.startMs));
        String app = TextUtils.isEmpty(e.appLabel) ? e.packageName : e.appLabel;
        String system = e.systemApp ? "  [system]" : "";
        StringBuilder b = new StringBuilder("├─ ").append(time).append("  ");
        switch (e.kind) {
            case AppUsageStore.KIND_APP_USAGE:
                b.append("📱 ").append(app).append(system)
                        .append("\n│   Used ").append(duration(e.durationMs));
                if (!TextUtils.isEmpty(e.detail)) b.append("\n│   ").append(e.detail);
                break;
            case "APP_DAILY_USAGE":
                b.append("📊 ").append(app).append(system)
                        .append("\n│   Daily total ").append(duration(e.durationMs));
                break;
            case KIND_PHONE_DAILY:
                appendDailyPhoneState(b, e);
                break;
            case AppUsageStore.KIND_SCREEN_ON:
                b.append("☀ Screen ON\n│   Interactive for ").append(duration(e.durationMs));
                break;
            case AppUsageStore.KIND_SCREEN_OFF:
                b.append("🌙 Screen OFF\n│   Off for ").append(duration(e.durationMs));
                break;
            case AppUsageStore.KIND_LOCKED:
                b.append("🔒 Phone locked");
                break;
            case AppUsageStore.KIND_UNLOCKED:
                b.append("🔓 Phone unlocked");
                break;
            case AppUsageStore.KIND_INSTALLED:
                b.append("⬇ Installed · ").append(app).append(system);
                appendSource(b, e);
                break;
            case AppUsageStore.KIND_UPDATED:
                b.append("↻ Updated · ").append(app).append(system);
                appendSource(b, e);
                break;
            case AppUsageStore.KIND_UNINSTALLED:
                b.append("🗑 Uninstalled · ").append(app).append(system);
                appendSource(b, e);
                break;
            case AppUsageStore.KIND_APP_INTERACTION:
                b.append("• User interaction · ").append(app).append(system);
                if (!TextUtils.isEmpty(e.detail)) b.append("\n│   ").append(e.detail);
                break;
            case AppUsageStore.KIND_SHORTCUT:
                b.append("↗ Shortcut · ").append(app).append(system);
                if (!TextUtils.isEmpty(e.detail)) b.append("\n│   ").append(e.detail);
                break;
            default:
                b.append(e.kind);
                if (!TextUtils.isEmpty(app)) b.append(" · ").append(app);
        }
        if (!TextUtils.isEmpty(e.packageName)
                && !AppUsageStore.KIND_APP_USAGE.equals(e.kind)
                && !"APP_DAILY_USAGE".equals(e.kind)) {
            b.append("\n│   ").append(e.packageName);
        }
        return b.toString();
    }

    private void appendDailyPhoneState(StringBuilder b, AppUsageStore.TimelineEntry e) {
        long screenOff = 0L;
        int unlocks = 0;
        if (!TextUtils.isEmpty(e.detail)) {
            String[] parts = e.detail.split("\\|", -1);
            if (parts.length > 0) {
                try {
                    screenOff = Long.parseLong(parts[0]);
                } catch (NumberFormatException ignored) { }
            }
            if (parts.length > 1) {
                try {
                    unlocks = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) { }
            }
        }
        b.append("🕘 Daily phone summary")
                .append("\n│   Screen on: ").append(duration(e.durationMs))
                .append(" · off: ").append(duration(screenOff))
                .append(" · unlocks: ").append(unlocks);
    }

    private void appendSource(StringBuilder b, AppUsageStore.TimelineEntry e) {
        if (!TextUtils.isEmpty(e.source)) {
            b.append("\n│   Source: ").append(e.source);
        } else {
            b.append("\n│   Source: not exposed by Android");
        }
        if (!TextUtils.isEmpty(e.sourceUri)) {
            b.append("\n│   Store page: ").append(e.sourceUri);
        }
    }

    private static String duration(long ms) {
        if (ms <= 0L) return "0m";
        long seconds = ms / 1000L;
        if (seconds < 60L) return seconds + "s";
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        if (days > 0L) return days + "d " + (hours % 24L) + "h";
        if (hours > 0L) return hours + "h " + (minutes % 60L) + "m";
        return minutes + "m";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Row {
        final boolean header;
        final String text;

        Row(boolean header, String text) {
            this.header = header;
            this.text = text;
        }
    }

    private final class TimelineAdapter extends RecyclerView.Adapter<TimelineAdapter.Holder> {
        private List<Row> rows = Collections.emptyList();

        void setRows(List<Row> rows) {
            this.rows = rows;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView text = new TextView(parent.getContext());
            text.setPadding(dp(8), dp(7), dp(8), dp(7));
            text.setTextIsSelectable(true);
            return new Holder(text);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Row row = rows.get(position);
            holder.text.setText(row.text);
            holder.text.setTextSize(row.header ? 17f : 13f);
            holder.text.setTypeface(null, row.header
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            holder.text.setPadding(dp(row.header ? 4 : 12), dp(row.header ? 12 : 6),
                    dp(4), dp(6));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView text;

            Holder(TextView text) {
                super(text);
                this.text = text;
            }
        }
    }
}
