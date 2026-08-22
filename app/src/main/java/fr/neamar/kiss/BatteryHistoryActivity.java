package fr.neamar.kiss;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import fr.neamar.kiss.battery.BatteryHistoryStore;
import fr.neamar.kiss.battery.BatteryPeriodBarView;
import fr.neamar.kiss.forwarder.InterfaceTweaks;

public final class BatteryHistoryActivity extends AppCompatActivity {
    private static final long DAY = 86_400_000L;
    private BatteryHistoryStore store;
    private BatteryPeriodBarView graph;
    private TextView periodSummary;
    private LinearLayout sessionsContainer;
    private Button daily;
    private Button weekly;
    private Button monthly;
    private int maxSessions = 20;
    private Mode mode = Mode.WEEKLY;

    private enum Mode { DAILY, WEEKLY, MONTHLY }

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        InterfaceTweaks.applySettingsTheme(this,
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this));
        super.onCreate(savedInstanceState);
        store = new BatteryHistoryStore(this);
        setTitle("Battery History");
        setContentView(buildUi());
        refresh();
    }

    @Override protected void onDestroy() {
        if (store != null) store.close();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout selector = new LinearLayout(this);
        selector.setOrientation(LinearLayout.HORIZONTAL);
        selector.setGravity(Gravity.CENTER);
        daily = periodButton("Daily", Mode.DAILY);
        weekly = periodButton("Weekly", Mode.WEEKLY);
        monthly = periodButton("Monthly", Mode.MONTHLY);
        selector.addView(daily, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selector.addView(weekly, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selector.addView(monthly, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(selector);

        graph = new BatteryPeriodBarView(this);
        root.addView(graph, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

        TextView legend = body();
        legend.setText("Blue = charged %   ·   Green = battery usage %");
        legend.setGravity(Gravity.CENTER);
        root.addView(legend);

        periodSummary = cardText();
        root.addView(periodSummary, cardParams());

        TextView historyTitle = title("SESSIONS");
        root.addView(historyTitle);
        sessionsContainer = new LinearLayout(this);
        sessionsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(sessionsContainer);

        Button more = new Button(this);
        more.setText("SHOW LAST 100 SESSIONS");
        more.setOnClickListener(v -> {
            maxSessions = 100;
            renderSessions();
            more.setVisibility(View.GONE);
        });
        root.addView(more);
        return scroll;
    }

    private Button periodButton(String label, Mode target) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            mode = target;
            refresh();
        });
        return b;
    }

    private void refresh() {
        updatePeriodButtons();
        long window = mode == Mode.DAILY ? 7L * DAY : mode == Mode.WEEKLY ? 8L * 7L * DAY : 180L * DAY;
        List<BatteryHistoryStore.SamplePoint> points = store.recentSamples(window, 12000);
        graph.setBars(buildBars(points));
        updatePeriodSummary(points);
        renderSessions();
    }

    private void updatePeriodButtons() {
        daily.setEnabled(mode != Mode.DAILY);
        weekly.setEnabled(mode != Mode.WEEKLY);
        monthly.setEnabled(mode != Mode.MONTHLY);
    }

    private List<BatteryPeriodBarView.Bar> buildBars(List<BatteryHistoryStore.SamplePoint> points) {
        int buckets = mode == Mode.DAILY ? 7 : mode == Mode.WEEKLY ? 8 : 6;
        long bucketMs = mode == Mode.DAILY ? DAY : mode == Mode.WEEKLY ? 7L * DAY : 30L * DAY;
        long now = System.currentTimeMillis();
        long start = now - buckets * bucketMs;
        float[] charged = new float[buckets];
        float[] used = new float[buckets];
        BatteryHistoryStore.SamplePoint previous = null;
        for (BatteryHistoryStore.SamplePoint p : points) {
            if (previous != null) {
                long mid = (previous.ts + p.ts) / 2L;
                int index = (int) ((mid - start) / bucketMs);
                if (index >= 0 && index < buckets) {
                    int delta = p.level - previous.level;
                    if (delta > 0) charged[index] += delta;
                    else if (delta < 0) used[index] += -delta;
                }
            }
            previous = p;
        }
        List<BatteryPeriodBarView.Bar> out = new ArrayList<>();
        SimpleDateFormat dayFmt = new SimpleDateFormat("d", Locale.getDefault());
        SimpleDateFormat monthFmt = new SimpleDateFormat("MMM", Locale.getDefault());
        for (int i = 0; i < buckets; i++) {
            long bucketStart = start + i * bucketMs;
            String label;
            if (mode == Mode.MONTHLY) label = monthFmt.format(new Date(bucketStart));
            else if (mode == Mode.WEEKLY) label = "W" + (i + 1);
            else label = dayFmt.format(new Date(bucketStart));
            out.add(new BatteryPeriodBarView.Bar(label, charged[i], used[i]));
        }
        return out;
    }

    private void updatePeriodSummary(List<BatteryHistoryStore.SamplePoint> points) {
        double charged = 0d;
        double used = 0d;
        BatteryHistoryStore.SamplePoint previous = null;
        for (BatteryHistoryStore.SamplePoint p : points) {
            if (previous != null) {
                int d = p.level - previous.level;
                if (d > 0) charged += d;
                else if (d < 0) used += -d;
            }
            previous = p;
        }
        double wearCycles = charged / 100d;
        double coverage = charged > 0d ? Math.min(100d, used / charged * 100d) : Double.NaN;
        String range = rangeText(points);
        String efficiency = Double.isNaN(coverage) ? "Learning…" : String.format(Locale.US, "%.0f%%", coverage);
        periodSummary.setText(range
                + "\n\n🔵 Charged        " + String.format(Locale.US, "%.0f%%", charged)
                + "\n⚪ Battery usage  " + String.format(Locale.US, "%.0f%%", used)
                + "\n🟠 Battery wear   " + String.format(Locale.US, "%.2f cycles", wearCycles)
                + "\n🟢 Efficiency     " + efficiency
                + "\n\nSessions recorded: " + store.recentSessions(100).size());
    }

    private String rangeText(List<BatteryHistoryStore.SamplePoint> points) {
        if (points.isEmpty()) return "No history yet";
        SimpleDateFormat fmt = new SimpleDateFormat("M/d/yy", Locale.getDefault());
        return fmt.format(new Date(points.get(0).ts)) + " to "
                + fmt.format(new Date(points.get(points.size() - 1).ts));
    }

    private void renderSessions() {
        sessionsContainer.removeAllViews();
        List<BatteryHistoryStore.SessionSummary> sessions = store.recentSessions(maxSessions);
        List<BatteryHistoryStore.SamplePoint> all = store.recentSamples(180L * DAY, 12000);
        if (sessions.isEmpty()) {
            TextView empty = body();
            empty.setText("Collecting charging and discharging sessions…");
            sessionsContainer.addView(empty);
            return;
        }
        for (int i = sessions.size() - 1; i >= 0; i--) {
            sessionsContainer.addView(sessionCard(sessions.get(i), all), cardParams());
        }
    }

    private View sessionCard(BatteryHistoryStore.SessionSummary s, List<BatteryHistoryStore.SamplePoint> all) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackgroundColor(0xDD20211F);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView heading = body();
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setText((s.charging ? "Charged for " : "Used for ") + duration(s.durationMs()));
        TextView time = body();
        time.setGravity(Gravity.END);
        time.setText(new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(new Date(s.endMs)));
        top.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(time, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.HORIZONTAL);
        TextView levels = body();
        levels.setTextSize(23f);
        levels.setText(s.startLevel + "% to " + s.endLevel + "%");
        TextView delta = body();
        delta.setTextSize(22f);
        delta.setGravity(Gravity.END);
        int d = s.endLevel - s.startLevel;
        delta.setText((d >= 0 ? "+" : "") + d + "%");
        delta.setTextColor(d < 0 ? 0xFFFF5252 : 0xFF7CB342);
        middle.addView(levels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        middle.addView(delta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(middle);

        TextView detail = body();
        long screenOnMs = screenOnMs(s, all);
        double screenShare = s.durationMs() > 0 ? screenOnMs * 100d / s.durationMs() : 0d;
        String mah = s.deliveredUah > 0 ? String.format(Locale.US, "%.0f mAh", s.deliveredUah / 1000d) : "mAh learning…";
        if (s.charging) {
            double wear = Math.max(0, s.endLevel - s.startLevel) / 100d;
            detail.setText("Battery wear: " + String.format(Locale.US, "%.2f cycles", wear) + "                         " + mah);
        } else {
            detail.setText("Screen on time: " + duration(screenOnMs) + " (" + String.format(Locale.US, "%.0f%%", screenShare) + ")                         " + mah);
        }
        card.addView(detail);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(Math.min(100, Math.abs(d)));
        card.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        return card;
    }

    private long screenOnMs(BatteryHistoryStore.SessionSummary s, List<BatteryHistoryStore.SamplePoint> points) {
        long total = 0L;
        BatteryHistoryStore.SamplePoint previous = null;
        for (BatteryHistoryStore.SamplePoint p : points) {
            if (p.ts < s.startMs || p.ts > s.endMs) continue;
            if (previous != null && previous.screenOn) total += Math.max(0L, p.ts - previous.ts);
            previous = p;
        }
        return total;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(6), 0, dp(6));
        return p;
    }

    private TextView cardText() {
        TextView v = body();
        v.setPadding(dp(16), dp(14), dp(16), dp(14));
        v.setBackgroundColor(0xDD20211F);
        return v;
    }

    private TextView title(String text) {
        TextView v = body();
        v.setText(text);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setTextSize(16f);
        v.setPadding(0, dp(18), 0, dp(5));
        return v;
    }

    private TextView body() {
        TextView v = new TextView(this);
        v.setTextSize(15f);
        v.setTextColor(0xFFE8EAED);
        v.setLineSpacing(0f, 1.15f);
        return v;
    }

    private String duration(long ms) {
        long minutes = Math.max(0L, ms / 60_000L);
        if (minutes < 60) return minutes + "m";
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
