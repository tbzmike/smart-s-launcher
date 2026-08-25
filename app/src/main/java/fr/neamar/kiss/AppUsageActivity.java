package fr.neamar.kiss;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import fr.neamar.kiss.appusage.AppUsageStore;
import fr.neamar.kiss.appusage.AppUsageTracker;
import fr.neamar.kiss.ui.AutoMarqueeTextView;

/**
 * Detailed, local, 365-day phone usage explorer.
 *
 * The screen deliberately keeps all Android/SQLite work off the rendering path. Switching tabs,
 * scrolling, filtering and opening details use the already-loaded in-memory snapshot. A system
 * usage sync is only requested on Activity resume or explicit refresh.
 */
public final class AppUsageActivity extends AppCompatActivity {
    private static final String KIND_PHONE_DAILY = "PHONE_DAILY";
    private static final String KIND_APP_DAILY = "APP_DAILY_USAGE";
    private static final String KIND_DEVICE_BOOT = "DEVICE_BOOT";

    private static final int VIEW_OVERVIEW = 0;
    private static final int VIEW_HEATMAP = 1;
    private static final int VIEW_TIMELINE = 2;
    private static final int VIEW_DETAILED = 3;
    private static final int VIEW_INSTALLS = 4;

    private static final int BG = Color.rgb(31, 33, 36);
    private static final int BG_DARK = Color.BLACK;
    private static final int BG_HEADER = Color.rgb(46, 48, 51);
    private static final int TEXT = Color.rgb(245, 245, 245);
    private static final int TEXT_SECONDARY = Color.rgb(205, 205, 205);
    private static final int TEXT_MUTED = Color.rgb(155, 155, 155);
    private static final int ACCENT = Color.rgb(255, 234, 226);
    private static final int TIMELINE_RAIL = Color.rgb(112, 46, 4);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "smart-s-app-usage-ui");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private final List<TextView> tabLabels = new ArrayList<>();
    private final List<View> tabUnderlines = new ArrayList<>();
    private final LruCache<String, Drawable.ConstantState> iconCache = new LruCache<>(96);

    private TextView subtitle;
    private TextView status;
    private Button grantAccess;
    private Button rangeButton;
    private ProgressBar progress;
    private RecyclerView list;

    private int activeView = VIEW_TIMELINE;
    private int rangeDays = 1;
    private boolean showUserApps = true;
    private boolean showSystemApps = true;
    private boolean showScreenEvents = true;
    private boolean showInteractionEvents = false;
    private boolean showPackageEvents = true;
    private String appFilter = "";

    @Nullable private Snapshot snapshot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload(true);
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
        root.setBackgroundColor(BG);

        root.addView(buildTopBar());
        root.addView(buildTabs());

        LinearLayout accessBar = new LinearLayout(this);
        accessBar.setOrientation(LinearLayout.HORIZONTAL);
        accessBar.setGravity(Gravity.CENTER_VERTICAL);
        accessBar.setPadding(dp(12), dp(4), dp(10), dp(4));
        accessBar.setBackgroundColor(Color.rgb(25, 25, 25));

        status = new TextView(this);
        status.setTextColor(TEXT_MUTED);
        status.setTextSize(11f);
        accessBar.addView(status, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        grantAccess = new Button(this);
        grantAccess.setText("Usage access");
        grantAccess.setAllCaps(false);
        grantAccess.setTextSize(11f);
        grantAccess.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        accessBar.addView(grantAccess, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        root.addView(accessBar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        list = new RecyclerView(this);
        list.setBackgroundColor(BG);
        list.setItemAnimator(null);
        list.setHasFixedSize(false);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(6), dp(6));
        bar.setBackgroundColor(BG_DARK);

        ImageButton back = toolbarButton(android.R.drawable.ic_media_previous, "Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Usage history");
        title.setTextColor(TEXT);
        title.setTextSize(24f);
        title.setSingleLine(true);
        titles.addView(title);
        subtitle = new TextView(this);
        subtitle.setText("Daily");
        subtitle.setTextColor(TEXT_SECONDARY);
        subtitle.setTextSize(13f);
        titles.addView(subtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        rangeButton = new Button(this);
        rangeButton.setAllCaps(false);
        rangeButton.setText("Daily ▾");
        rangeButton.setTextColor(TEXT);
        rangeButton.setTextSize(12f);
        rangeButton.setBackgroundColor(Color.TRANSPARENT);
        rangeButton.setOnClickListener(this::showRangeMenu);
        bar.addView(rangeButton, new LinearLayout.LayoutParams(dp(88), dp(48)));

        ImageButton filter = toolbarButton(android.R.drawable.ic_menu_search, "Filter usage");
        filter.setOnClickListener(v -> showFilterDialog());
        bar.addView(filter, new LinearLayout.LayoutParams(dp(48), dp(48)));

        ImageButton refresh = toolbarButton(android.R.drawable.ic_popup_sync, "Refresh usage");
        refresh.setOnClickListener(v -> reload(true));
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return bar;
    }

    private ImageButton toolbarButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(Color.WHITE);
        button.setContentDescription(description);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        return button;
    }

    private View buildTabs() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(BG_DARK);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"OVERVIEW", "HEATMAP VIEW", "TIMELINE VIEW", "DETAILED VIEW", "INSTALLS HISTORY"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(10), 0, dp(10), 0);

            TextView label = new TextView(this);
            label.setText(names[i]);
            label.setTextColor(TEXT);
            label.setTextSize(16f);
            label.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setPadding(dp(4), dp(12), dp(4), dp(10));
            label.setOnClickListener(v -> selectView(index));
            tab.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            View underline = new View(this);
            underline.setBackgroundColor(index == activeView ? ACCENT : Color.TRANSPARENT);
            tab.addView(underline, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
            tab.setOnClickListener(v -> selectView(index));
            tabs.addView(tab, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tabLabels.add(label);
            tabUnderlines.add(underline);
        }
        scroll.addView(tabs);
        return scroll;
    }

    private void selectView(int view) {
        if (activeView == view && snapshot != null) return;
        activeView = view;
        for (int i = 0; i < tabLabels.size(); i++) {
            tabLabels.get(i).setTypeface(Typeface.DEFAULT,
                    i == activeView ? Typeface.BOLD : Typeface.NORMAL);
            tabUnderlines.get(i).setBackgroundColor(i == activeView ? ACCENT : Color.TRANSPARENT);
        }
        render();
    }

    private void showRangeMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Today · Daily");
        menu.getMenu().add(0, 7, 1, "Last 7 days");
        menu.getMenu().add(0, 30, 2, "Last 30 days");
        menu.getMenu().add(0, 365, 3, "Last 365 days");
        menu.setOnMenuItemClickListener(item -> {
            rangeDays = item.getItemId();
            updateRangeLabel();
            reload(false);
            return true;
        });
        menu.show();
    }

    private void updateRangeLabel() {
        String label;
        if (rangeDays == 1) label = "Daily";
        else if (rangeDays == 7) label = "7 days";
        else if (rangeDays == 30) label = "30 days";
        else label = "365 days";
        subtitle.setText(label);
        rangeButton.setText(label + " ▾");
    }

    private void showFilterDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        EditText query = new EditText(this);
        query.setHint("App or package name");
        query.setSingleLine(true);
        query.setText(appFilter);
        box.addView(query, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        CheckBox user = check("User-installed apps", showUserApps);
        CheckBox system = check("System apps explicitly used", showSystemApps);
        CheckBox screen = check("Screen / lock / unlock / boot events", showScreenEvents);
        CheckBox interaction = check("User interaction & shortcut events", showInteractionEvents);
        CheckBox packages = check("Install / update / uninstall events", showPackageEvents);
        box.addView(user);
        box.addView(system);
        box.addView(screen);
        box.addView(interaction);
        box.addView(packages);

        new AlertDialog.Builder(this)
                .setTitle("Filter usage history")
                .setView(box)
                .setNeutralButton("Clear", (d, which) -> {
                    appFilter = "";
                    showUserApps = true;
                    showSystemApps = true;
                    showScreenEvents = true;
                    showInteractionEvents = false;
                    showPackageEvents = true;
                    render();
                })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (d, which) -> {
                    appFilter = query.getText() == null ? "" : query.getText().toString().trim();
                    showUserApps = user.isChecked();
                    showSystemApps = system.isChecked();
                    showScreenEvents = screen.isChecked();
                    showInteractionEvents = interaction.isChecked();
                    showPackageEvents = packages.isChecked();
                    render();
                })
                .show();
    }

    private CheckBox check(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setChecked(checked);
        return box;
    }

    private void reload(boolean syncFirst) {
        final int generation = loadGeneration.incrementAndGet();
        final boolean enabled = AppUsageTracker.isEnabled(this);
        final boolean access = AppUsageTracker.hasUsageAccess(this);
        grantAccess.setVisibility(access ? View.GONE : View.VISIBLE);
        status.setText(enabled
                ? (access ? "Tracking ON · 365-day local history"
                          : "Tracking ON · Usage Access is required")
                : "Tracking OFF · stored history is still available");
        progress.setVisibility(View.VISIBLE);

        final int days = rangeDays;
        executor.execute(() -> {
            if (syncFirst && enabled && access) {
                AppUsageTracker.syncNow(getApplicationContext());
            }
            long now = System.currentTimeMillis();
            long since = days == 1 ? AppUsageStore.startOfDay(now)
                    : now - days * 24L * 60L * 60L * 1000L;
            AppUsageStore store = AppUsageStore.get(getApplicationContext());
            List<AppUsageStore.TimelineEntry> exact = store.getTimeline(since, 12000);
            List<AppUsageStore.TimelineEntry> daily = store.getDailyUsageTimeline(since, 7000);
            List<AppUsageStore.DailyPhoneState> phone = store.getDailyPhoneStates(since, 366);
            AppUsageStore.Summary totals = store.getSummary(since, now);
            Snapshot fresh = new Snapshot(since, now, exact, daily, phone, totals);
            runOnUiThread(() -> {
                if (generation != loadGeneration.get() || isFinishing()) return;
                snapshot = fresh;
                progress.setVisibility(View.GONE);
                render();
            });
        });
    }

    private void render() {
        Snapshot data = snapshot;
        if (data == null || list == null) return;
        switch (activeView) {
            case VIEW_OVERVIEW:
                list.setLayoutManager(new LinearLayoutManager(this));
                list.setAdapter(new OverviewAdapter(data));
                break;
            case VIEW_HEATMAP:
                list.setLayoutManager(new LinearLayoutManager(this));
                list.setAdapter(new HeatmapAdapter(buildHeatmap(data)));
                break;
            case VIEW_DETAILED:
                list.setLayoutManager(new LinearLayoutManager(this));
                list.setAdapter(new DetailedAdapter(buildAggregates(data)));
                break;
            case VIEW_INSTALLS:
                GridLayoutManager grid = new GridLayoutManager(this, 2);
                InstallAdapter installs = new InstallAdapter(buildInstallRows(data));
                grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                    @Override public int getSpanSize(int position) {
                        return installs.isHeader(position) ? 2 : 1;
                    }
                });
                list.setLayoutManager(grid);
                list.setAdapter(installs);
                break;
            case VIEW_TIMELINE:
            default:
                list.setLayoutManager(new LinearLayoutManager(this));
                list.setAdapter(new TimelineAdapter(buildTimelineRows(data)));
                break;
        }
    }

    private List<AppUsageStore.TimelineEntry> filteredExact(Snapshot data) {
        List<AppUsageStore.TimelineEntry> out = new ArrayList<>();
        String q = appFilter.toLowerCase(Locale.ROOT);
        for (AppUsageStore.TimelineEntry e : data.exact) {
            boolean packageBacked = !TextUtils.isEmpty(e.packageName);
            if (packageBacked) {
                if (e.systemApp && !showSystemApps) continue;
                if (!e.systemApp && !showUserApps) continue;
                if (!q.isEmpty()) {
                    String label = TextUtils.isEmpty(e.appLabel) ? "" : e.appLabel.toLowerCase(Locale.ROOT);
                    String pkg = e.packageName.toLowerCase(Locale.ROOT);
                    if (!label.contains(q) && !pkg.contains(q)) continue;
                }
            } else if (!q.isEmpty()) {
                continue;
            }

            if (isScreenKind(e.kind) && !showScreenEvents) continue;
            if (isInteractionKind(e.kind) && !showInteractionEvents) continue;
            if (isPackageKind(e.kind) && !showPackageEvents) continue;
            out.add(e);
        }
        out.sort(Comparator.comparingLong((AppUsageStore.TimelineEntry e) -> e.startMs).reversed());
        return out;
    }

    private boolean isScreenKind(String kind) {
        return AppUsageStore.KIND_SCREEN_ON.equals(kind)
                || AppUsageStore.KIND_SCREEN_OFF.equals(kind)
                || AppUsageStore.KIND_LOCKED.equals(kind)
                || AppUsageStore.KIND_UNLOCKED.equals(kind)
                || KIND_DEVICE_BOOT.equals(kind);
    }

    private boolean isInteractionKind(String kind) {
        return AppUsageStore.KIND_APP_INTERACTION.equals(kind)
                || AppUsageStore.KIND_SHORTCUT.equals(kind);
    }

    private boolean isPackageKind(String kind) {
        return AppUsageStore.KIND_INSTALLED.equals(kind)
                || AppUsageStore.KIND_UPDATED.equals(kind)
                || AppUsageStore.KIND_UNINSTALLED.equals(kind);
    }

    private List<TimelineRow> buildTimelineRows(Snapshot data) {
        List<AppUsageStore.TimelineEntry> entries = filteredExact(data);
        if (entries.isEmpty()) return Collections.singletonList(TimelineRow.empty());
        List<TimelineRow> rows = new ArrayList<>();
        SimpleDateFormat day = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault());
        String previous = null;
        for (AppUsageStore.TimelineEntry e : entries) {
            String current = day.format(new Date(e.startMs));
            if (!TextUtils.equals(previous, current)) {
                rows.add(TimelineRow.header(current));
                previous = current;
            }
            rows.add(TimelineRow.event(e));
        }
        return rows;
    }

    private List<InstallRow> buildInstallRows(Snapshot data) {
        List<AppUsageStore.TimelineEntry> events = new ArrayList<>();
        for (AppUsageStore.TimelineEntry e : filteredExact(data)) {
            if (isPackageKind(e.kind)) events.add(e);
        }
        if (events.isEmpty()) return Collections.singletonList(InstallRow.empty());
        events.sort(Comparator.comparingLong((AppUsageStore.TimelineEntry e) -> e.startMs).reversed());
        List<InstallRow> rows = new ArrayList<>();
        SimpleDateFormat day = new SimpleDateFormat("MMM d", Locale.getDefault());
        String previous = null;
        for (AppUsageStore.TimelineEntry e : events) {
            String current = day.format(new Date(e.startMs)).toUpperCase(Locale.getDefault());
            if (!TextUtils.equals(previous, current)) {
                rows.add(InstallRow.header(current));
                previous = current;
            }
            rows.add(InstallRow.event(e));
        }
        return rows;
    }

    private List<AppAggregate> buildAggregates(Snapshot data) {
        Map<String, AppAggregate> map = new LinkedHashMap<>();
        String q = appFilter.toLowerCase(Locale.ROOT);
        for (AppUsageStore.TimelineEntry e : data.daily) {
            if (TextUtils.isEmpty(e.packageName)) continue;
            if (e.systemApp && !showSystemApps) continue;
            if (!e.systemApp && !showUserApps) continue;
            if (!q.isEmpty() && !matches(e, q)) continue;
            AppAggregate a = aggregate(map, e);
            a.dailyUsageMs += e.durationMs;
        }
        for (AppUsageStore.TimelineEntry e : data.exact) {
            if (TextUtils.isEmpty(e.packageName)) continue;
            if (e.systemApp && !showSystemApps) continue;
            if (!e.systemApp && !showUserApps) continue;
            if (!q.isEmpty() && !matches(e, q)) continue;
            AppAggregate a = aggregate(map, e);
            if (AppUsageStore.KIND_APP_USAGE.equals(e.kind)) {
                a.exactUsageMs += e.durationMs;
                a.sessions++;
                a.longestSessionMs = Math.max(a.longestSessionMs, e.durationMs);
                a.firstUsedMs = Math.min(a.firstUsedMs, e.startMs);
                a.lastUsedMs = Math.max(a.lastUsedMs, e.endMs > 0L ? e.endMs : e.startMs);
            } else if (AppUsageStore.KIND_APP_INTERACTION.equals(e.kind)) {
                a.interactions++;
            } else if (AppUsageStore.KIND_SHORTCUT.equals(e.kind)) {
                a.shortcuts++;
            }
        }
        List<AppAggregate> out = new ArrayList<>(map.values());
        out.sort(Comparator.comparingLong(AppAggregate::displayUsageMs).reversed()
                .thenComparing(a -> a.label == null ? "" : a.label, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private AppAggregate aggregate(Map<String, AppAggregate> map, AppUsageStore.TimelineEntry e) {
        AppAggregate current = map.get(e.packageName);
        if (current != null) return current;
        String label = TextUtils.isEmpty(e.appLabel) ? e.packageName : e.appLabel;
        current = new AppAggregate(e.packageName, label, e.systemApp);
        map.put(e.packageName, current);
        return current;
    }

    private boolean matches(AppUsageStore.TimelineEntry e, String queryLower) {
        String label = TextUtils.isEmpty(e.appLabel) ? "" : e.appLabel.toLowerCase(Locale.ROOT);
        String pkg = e.packageName == null ? "" : e.packageName.toLowerCase(Locale.ROOT);
        return label.contains(queryLower) || pkg.contains(queryLower);
    }

    private List<HeatmapDay> buildHeatmap(Snapshot data) {
        Map<Long, HeatmapDay> days = new HashMap<>();
        for (AppUsageStore.TimelineEntry e : filteredExact(data)) {
            if (!AppUsageStore.KIND_APP_USAGE.equals(e.kind) || e.durationMs <= 0L) continue;
            long cursor = e.startMs;
            long end = e.endMs > cursor ? e.endMs : cursor + e.durationMs;
            while (cursor < end) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(cursor);
                int hour = cal.get(Calendar.HOUR_OF_DAY);
                long day = AppUsageStore.startOfDay(cursor);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                cal.add(Calendar.HOUR_OF_DAY, 1);
                long segmentEnd = Math.min(end, cal.getTimeInMillis());
                long ms = Math.max(0L, segmentEnd - cursor);
                HeatmapDay heat = days.computeIfAbsent(day, HeatmapDay::new);
                heat.hours[hour] += ms;
                heat.totalMs += ms;
                cursor = segmentEnd;
            }
        }
        List<HeatmapDay> out = new ArrayList<>(days.values());
        out.sort(Comparator.comparingLong((HeatmapDay d) -> d.dayMs).reversed());
        return out;
    }

    @NonNull
    private Drawable appIcon(@Nullable String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return getDrawable(android.R.drawable.sym_def_app_icon);
        }
        Drawable.ConstantState state = iconCache.get(packageName);
        if (state != null) return state.newDrawable(getResources());
        try {
            Drawable icon = getPackageManager().getApplicationIcon(packageName);
            Drawable.ConstantState constant = icon.getConstantState();
            if (constant != null) iconCache.put(packageName, constant);
            return icon;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            Drawable fallback = getDrawable(android.R.drawable.sym_def_app_icon);
            return fallback == null ? new GradientDrawable() : fallback;
        }
    }

    private void showAppDetails(String packageName) {
        Snapshot data = snapshot;
        if (data == null || TextUtils.isEmpty(packageName)) return;
        AppAggregate target = null;
        for (AppAggregate a : buildAggregates(data)) {
            if (packageName.equals(a.packageName)) {
                target = a;
                break;
            }
        }
        if (target == null) {
            target = new AppAggregate(packageName, packageName, false);
        }
        final AppAggregate a = target;
        AppUsageStore.PackageState state = AppUsageStore.get(this).getPackageState(packageName);

        String currentVersion = "Not currently installed";
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = getPackageManager().getPackageInfo(packageName,
                        PackageManager.PackageInfoFlags.of(0L));
            } else {
                //noinspection deprecation
                info = getPackageManager().getPackageInfo(packageName, 0);
            }
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            currentVersion = (TextUtils.isEmpty(info.versionName) ? "version" : "v" + info.versionName)
                    + " · code " + code;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }

        StringBuilder details = new StringBuilder();
        details.append(a.systemApp ? "System app explicitly used" : "User-installed app")
                .append("\n\nPackage\n").append(packageName)
                .append("\n\nUsage in selected period\n").append(verboseDuration(a.displayUsageMs()))
                .append("\nExact sessions retained\n").append(a.sessions)
                .append("\nExact session time retained\n").append(verboseDuration(a.exactUsageMs));
        if (a.sessions > 0) {
            details.append("\nAverage exact session\n")
                    .append(verboseDuration(a.exactUsageMs / a.sessions))
                    .append("\nLongest exact session\n")
                    .append(verboseDuration(a.longestSessionMs));
        }
        details.append("\nInteractions recorded\n").append(a.interactions)
                .append("\nShortcut invocations\n").append(a.shortcuts);
        if (a.firstUsedMs != Long.MAX_VALUE) {
            details.append("\nFirst exact use in period\n").append(dateTime(a.firstUsedMs))
                    .append("\nLast exact use in period\n").append(dateTime(a.lastUsedMs));
        }
        details.append("\n\nCurrent package version\n").append(currentVersion);
        if (state != null) {
            if (state.firstInstallMs > 0L) {
                details.append("\nFirst install time Android exposes\n")
                        .append(dateTime(state.firstInstallMs));
            }
            if (state.lastUpdateMs > 0L) {
                details.append("\nLast update time Android exposes\n")
                        .append(dateTime(state.lastUpdateMs));
            }
            details.append("\nInstall source\n")
                    .append(TextUtils.isEmpty(state.source) ? "Not exposed by Android" : state.source);
            if (!TextUtils.isEmpty(state.sourceUri)) {
                details.append("\nKnown store page\n").append(state.sourceUri);
            }
        }

        ScrollView scroller = new ScrollView(this);
        TextView text = new TextView(this);
        text.setText(details.toString());
        text.setTextSize(14f);
        text.setTextIsSelectable(true);
        text.setPadding(dp(20), dp(12), dp(20), dp(12));
        scroller.addView(text);

        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setView(scroller)
                .setPositiveButton("Close", null)
                .setNeutralButton("App info", (d, which) -> openAppInfo(packageName));
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null) {
            dialog.setNegativeButton("Open", (d, which) -> {
                try { startActivity(launch); } catch (RuntimeException ignored) { }
            });
        }
        dialog.show();
    }

    private void openAppInfo(String packageName) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName));
            startActivity(intent);
        } catch (RuntimeException ignored) { }
    }

    private String eventTitle(AppUsageStore.TimelineEntry e) {
        String app = TextUtils.isEmpty(e.appLabel) ? e.packageName : e.appLabel;
        switch (e.kind) {
            case AppUsageStore.KIND_APP_USAGE: return app;
            case AppUsageStore.KIND_SCREEN_ON: return "Screen on (unlocked)";
            case AppUsageStore.KIND_SCREEN_OFF: return "Screen off (locked)";
            case AppUsageStore.KIND_LOCKED: return "Phone locked";
            case AppUsageStore.KIND_UNLOCKED: return "Phone unlocked";
            case AppUsageStore.KIND_INSTALLED: return "Installed · " + app;
            case AppUsageStore.KIND_UPDATED: return "Updated · " + app;
            case AppUsageStore.KIND_UNINSTALLED: return "Uninstalled · " + app;
            case AppUsageStore.KIND_APP_INTERACTION: return "Interaction · " + app;
            case AppUsageStore.KIND_SHORTCUT: return "Shortcut · " + app;
            case KIND_DEVICE_BOOT: return "Device boot";
            default: return TextUtils.isEmpty(app) ? e.kind : app;
        }
    }

    private String eventSubtitle(AppUsageStore.TimelineEntry e) {
        switch (e.kind) {
            case AppUsageStore.KIND_APP_USAGE:
                return verboseDuration(e.durationMs);
            case AppUsageStore.KIND_SCREEN_ON:
                return e.durationMs > 0L ? verboseDuration(e.durationMs) : "Screen became interactive";
            case AppUsageStore.KIND_SCREEN_OFF:
                return e.durationMs > 0L ? verboseDuration(e.durationMs) : "Screen became non-interactive";
            case AppUsageStore.KIND_LOCKED:
            case AppUsageStore.KIND_UNLOCKED:
            case KIND_DEVICE_BOOT:
                return "";
            case AppUsageStore.KIND_INSTALLED:
                return installActionDetail("installed", e);
            case AppUsageStore.KIND_UPDATED:
                return installActionDetail("updated", e);
            case AppUsageStore.KIND_UNINSTALLED:
                return installActionDetail("uninstalled", e);
            default:
                return TextUtils.isEmpty(e.detail) ? "" : e.detail;
        }
    }

    private String installActionDetail(String action, AppUsageStore.TimelineEntry e) {
        if (!TextUtils.isEmpty(e.detail)
                && !"Package installed".equals(e.detail)
                && !"Package updated".equals(e.detail)
                && !"Package removed".equals(e.detail)) {
            return e.detail;
        }
        return action;
    }

    private String eventExtra(AppUsageStore.TimelineEntry e) {
        StringBuilder out = new StringBuilder();
        if (e.systemApp && !TextUtils.isEmpty(e.packageName)) out.append("System app");
        if (AppUsageStore.KIND_APP_USAGE.equals(e.kind) && !TextUtils.isEmpty(e.detail)) {
            if (out.length() > 0) out.append(" · ");
            out.append(e.detail.replace("Foreground app session · ", ""));
        }
        if (isPackageKind(e.kind) && !TextUtils.isEmpty(e.source)) {
            if (out.length() > 0) out.append("\n");
            out.append("Source: ").append(e.source);
        }
        return out.toString();
    }

    private String eventGlyph(AppUsageStore.TimelineEntry e) {
        switch (e.kind) {
            case AppUsageStore.KIND_SCREEN_ON: return "☀";
            case AppUsageStore.KIND_SCREEN_OFF: return "☾";
            case AppUsageStore.KIND_LOCKED: return "🔒";
            case AppUsageStore.KIND_UNLOCKED: return "✓";
            case AppUsageStore.KIND_INSTALLED: return "↓";
            case AppUsageStore.KIND_UPDATED: return "↻";
            case AppUsageStore.KIND_UNINSTALLED: return "×";
            case KIND_DEVICE_BOOT: return "●";
            case AppUsageStore.KIND_APP_INTERACTION: return "•";
            case AppUsageStore.KIND_SHORTCUT: return "↗";
            default: return "";
        }
    }

    private static String verboseDuration(long ms) {
        if (ms <= 0L) return "0 seconds";
        long seconds = Math.max(0L, ms / 1000L);
        if (seconds < 60L) return seconds + (seconds == 1L ? " second" : " seconds");
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (minutes < 60L) {
            return minutes + "m" + (remainingSeconds == 0L ? "" : " " + remainingSeconds + "s");
        }
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        if (hours < 24L) return hours + "h" + (remainingMinutes == 0L ? "" : " " + remainingMinutes + "m");
        long days = hours / 24L;
        return days + "d " + (hours % 24L) + "h";
    }

    private String time(long ms) {
        return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(ms));
    }

    private String dateTime(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(ms));
    }

    private GradientDrawable oval(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class TimelineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_EVENT = 0;
        private static final int TYPE_HEADER = 1;
        private static final int TYPE_EMPTY = 2;
        private final List<TimelineRow> rows;

        TimelineAdapter(List<TimelineRow> rows) { this.rows = rows; }

        @Override public int getItemViewType(int position) {
            TimelineRow row = rows.get(position);
            return row.empty ? TYPE_EMPTY : row.header ? TYPE_HEADER : TYPE_EVENT;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            if (type == TYPE_HEADER) return new HeaderHolder(headerView(parent));
            if (type == TYPE_EMPTY) return new TextHolder(emptyView(parent));
            return new EventHolder(buildTimelineEventView(parent));
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder raw, int position) {
            TimelineRow row = rows.get(position);
            if (raw instanceof HeaderHolder) {
                ((HeaderHolder) raw).text.setText(row.label);
                return;
            }
            if (raw instanceof TextHolder) {
                ((TextHolder) raw).text.setText("No usage events are stored for this period yet.");
                return;
            }
            EventHolder holder = (EventHolder) raw;
            AppUsageStore.TimelineEntry e = row.entry;
            holder.time.setText(time(e.startMs));
            holder.title.setText(eventTitle(e));
            String sub = eventSubtitle(e);
            holder.subtitle.setText(sub);
            holder.subtitle.setVisibility(TextUtils.isEmpty(sub) ? View.GONE : View.VISIBLE);
            String extra = eventExtra(e);
            holder.extra.setText(extra);
            holder.extra.setVisibility(TextUtils.isEmpty(extra) ? View.GONE : View.VISIBLE);
            if (!TextUtils.isEmpty(e.packageName)) {
                holder.icon.setImageDrawable(appIcon(e.packageName));
                holder.icon.setVisibility(View.VISIBLE);
                holder.glyph.setVisibility(View.GONE);
            } else {
                holder.icon.setVisibility(View.GONE);
                holder.glyph.setVisibility(View.VISIBLE);
                holder.glyph.setText(eventGlyph(e));
            }
            holder.itemView.setOnClickListener(TextUtils.isEmpty(e.packageName)
                    ? null : v -> showAppDetails(e.packageName));
        }

        @Override public int getItemCount() { return rows.size(); }
    }

    private View buildTimelineEventView(ViewGroup parent) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(82));
        row.setPadding(0, dp(2), dp(8), dp(2));

        TextView time = new TextView(parent.getContext());
        time.setId(View.generateViewId());
        time.setTextColor(TEXT);
        time.setTextSize(12f);
        time.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        time.setPadding(dp(2), 0, dp(6), 0);
        row.addView(time, new LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout marker = new FrameLayout(parent.getContext());
        marker.setId(View.generateViewId());
        LinearLayout.LayoutParams markerLp = new LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.MATCH_PARENT);
        row.addView(marker, markerLp);

        View rail = new View(parent.getContext());
        rail.setBackgroundColor(TIMELINE_RAIL);
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL);
        marker.addView(rail, railLp);

        ImageView icon = new ImageView(parent.getContext());
        icon.setId(View.generateViewId());
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setBackground(oval(Color.WHITE));
        icon.setPadding(dp(3), dp(3), dp(3), dp(3));
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER);
        marker.addView(icon, iconLp);

        TextView glyph = new TextView(parent.getContext());
        glyph.setId(View.generateViewId());
        glyph.setGravity(Gravity.CENTER);
        glyph.setTextColor(Color.WHITE);
        glyph.setTextSize(20f);
        glyph.setTypeface(Typeface.DEFAULT_BOLD);
        glyph.setBackground(oval(TIMELINE_RAIL));
        FrameLayout.LayoutParams glyphLp = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER);
        marker.addView(glyph, glyphLp);

        LinearLayout body = new LinearLayout(parent.getContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(4), dp(7), 0, dp(7));
        TextView title = new AutoMarqueeTextView(parent.getContext());
        title.setId(View.generateViewId());
        title.setTextColor(TEXT);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(title);
        TextView sub = new TextView(parent.getContext());
        sub.setId(View.generateViewId());
        sub.setTextColor(TEXT);
        sub.setTextSize(14f);
        body.addView(sub);
        TextView extra = new AutoMarqueeTextView(parent.getContext());
        extra.setId(View.generateViewId());
        extra.setTextColor(TEXT_MUTED);
        extra.setTextSize(11f);
        body.addView(extra);
        row.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setTag(new int[]{time.getId(), icon.getId(), glyph.getId(), title.getId(), sub.getId(), extra.getId()});
        return row;
    }

    private final class EventHolder extends RecyclerView.ViewHolder {
        final TextView time;
        final ImageView icon;
        final TextView glyph;
        final TextView title;
        final TextView subtitle;
        final TextView extra;
        EventHolder(View item) {
            super(item);
            int[] ids = (int[]) item.getTag();
            time = item.findViewById(ids[0]);
            icon = item.findViewById(ids[1]);
            glyph = item.findViewById(ids[2]);
            title = item.findViewById(ids[3]);
            subtitle = item.findViewById(ids[4]);
            extra = item.findViewById(ids[5]);
        }
    }

    private View headerView(ViewGroup parent) {
        TextView text = new TextView(parent.getContext());
        text.setTextColor(TEXT);
        text.setTextSize(17f);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(dp(90), dp(9), dp(12), dp(9));
        text.setBackgroundColor(BG_HEADER);
        return text;
    }

    private View emptyView(ViewGroup parent) {
        TextView text = new TextView(parent.getContext());
        text.setTextColor(TEXT_SECONDARY);
        text.setTextSize(15f);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(24), dp(50), dp(24), dp(50));
        return text;
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView text;
        HeaderHolder(View item) { super(item); text = (TextView) item; }
    }

    private static final class TextHolder extends RecyclerView.ViewHolder {
        final TextView text;
        TextHolder(View item) { super(item); text = (TextView) item; }
    }

    private final class OverviewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Snapshot data;
        private final List<AppAggregate> apps;
        OverviewAdapter(Snapshot data) {
            this.data = data;
            this.apps = buildAggregates(data);
            if (apps.size() > 25) apps.subList(25, apps.size()).clear();
        }
        @Override public int getItemViewType(int p) { return p == 0 ? 0 : 1; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return type == 0 ? new TextHolder(buildOverviewSummary(parent)) : new AppHolder(buildAppRow(parent));
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position == 0) {
                AppUsageStore.Summary s = data.summary;
                ((TextHolder) holder).text.setText(
                        "TODAY / SELECTED PERIOD\n\n"
                                + "Screen on   " + verboseDuration(s.screenOnMs)
                                + "\nScreen off  " + verboseDuration(s.screenOffMs)
                                + "\nApp usage   " + verboseDuration(s.appUsageMs)
                                + "\nUnlocks     " + s.unlockCount
                                + "\nApps used   " + s.appsUsed
                                + "\n\nMOST USED APPS");
                return;
            }
            bindAppHolder((AppHolder) holder, apps.get(position - 1), true);
        }
        @Override public int getItemCount() { return 1 + apps.size(); }
    }

    private View buildOverviewSummary(ViewGroup parent) {
        TextView text = new TextView(parent.getContext());
        text.setTextColor(TEXT);
        text.setTextSize(16f);
        text.setLineSpacing(dp(3), 1f);
        text.setPadding(dp(22), dp(22), dp(22), dp(18));
        text.setBackground(rounded(Color.rgb(38, 40, 43), 0));
        return text;
    }

    private final class DetailedAdapter extends RecyclerView.Adapter<AppHolder> {
        private final List<AppAggregate> apps;
        DetailedAdapter(List<AppAggregate> apps) { this.apps = apps; }
        @NonNull @Override public AppHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new AppHolder(buildAppRow(parent));
        }
        @Override public void onBindViewHolder(@NonNull AppHolder holder, int position) {
            bindAppHolder(holder, apps.get(position), false);
        }
        @Override public int getItemCount() { return apps.size(); }
    }

    private View buildAppRow(ViewGroup parent) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(14), dp(10));
        row.setMinimumHeight(dp(84));

        ImageView icon = new ImageView(parent.getContext());
        icon.setId(View.generateViewId());
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout body = new LinearLayout(parent.getContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), 0, 0, 0);
        TextView title = new AutoMarqueeTextView(parent.getContext());
        title.setId(View.generateViewId());
        title.setTextColor(TEXT);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(title);
        TextView usage = new TextView(parent.getContext());
        usage.setId(View.generateViewId());
        usage.setTextColor(TEXT);
        usage.setTextSize(14f);
        body.addView(usage);
        TextView meta = new TextView(parent.getContext());
        meta.setId(View.generateViewId());
        meta.setTextColor(TEXT_MUTED);
        meta.setTextSize(11f);
        meta.setMaxLines(2);
        body.addView(meta);
        row.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setTag(new int[]{icon.getId(), title.getId(), usage.getId(), meta.getId()});
        return row;
    }

    private void bindAppHolder(AppHolder holder, AppAggregate a, boolean compact) {
        holder.icon.setImageDrawable(appIcon(a.packageName));
        holder.title.setText(a.label);
        holder.usage.setText(verboseDuration(a.displayUsageMs()));
        String type = a.systemApp ? "system" : "user";
        if (compact) {
            holder.meta.setText(a.sessions + " exact sessions · " + type);
        } else {
            String last = a.lastUsedMs > 0L ? time(a.lastUsedMs) : "not retained";
            holder.meta.setText(a.sessions + " sessions · longest "
                    + verboseDuration(a.longestSessionMs) + " · last " + last + " · " + type);
        }
        holder.itemView.setOnClickListener(v -> showAppDetails(a.packageName));
    }

    private static final class AppHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView usage;
        final TextView meta;
        AppHolder(View item) {
            super(item);
            int[] ids = (int[]) item.getTag();
            icon = item.findViewById(ids[0]);
            title = item.findViewById(ids[1]);
            usage = item.findViewById(ids[2]);
            meta = item.findViewById(ids[3]);
        }
    }

    private final class InstallAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int HEADER = 1;
        private static final int EVENT = 0;
        private static final int EMPTY = 2;
        private final List<InstallRow> rows;
        InstallAdapter(List<InstallRow> rows) { this.rows = rows; }
        boolean isHeader(int position) { return rows.get(position).header || rows.get(position).empty; }
        @Override public int getItemViewType(int position) {
            InstallRow row = rows.get(position);
            return row.empty ? EMPTY : row.header ? HEADER : EVENT;
        }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            if (type == HEADER) return new HeaderHolder(buildInstallHeader(parent));
            if (type == EMPTY) return new TextHolder(emptyView(parent));
            return new InstallHolder(buildInstallCard(parent));
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder raw, int position) {
            InstallRow row = rows.get(position);
            if (raw instanceof HeaderHolder) {
                ((HeaderHolder) raw).text.setText(row.label);
                return;
            }
            if (raw instanceof TextHolder) {
                ((TextHolder) raw).text.setText("No install, update or uninstall events are stored for this period.");
                return;
            }
            InstallHolder holder = (InstallHolder) raw;
            AppUsageStore.TimelineEntry e = row.entry;
            holder.icon.setImageDrawable(appIcon(e.packageName));
            holder.title.setText(TextUtils.isEmpty(e.appLabel) ? e.packageName : e.appLabel);
            String action = AppUsageStore.KIND_INSTALLED.equals(e.kind) ? "↓ installed"
                    : AppUsageStore.KIND_UPDATED.equals(e.kind) ? "↻ updated" : "× uninstalled";
            String detail = installActionDetail(action, e);
            holder.detail.setText(detail.startsWith(action) ? detail : action + " · " + detail);
            holder.time.setText(time(e.startMs));
            holder.source.setText(TextUtils.isEmpty(e.source) ? "Source not exposed" : e.source);
            holder.itemView.setOnClickListener(TextUtils.isEmpty(e.packageName)
                    ? null : v -> showAppDetails(e.packageName));
        }
        @Override public int getItemCount() { return rows.size(); }
    }

    private View buildInstallHeader(ViewGroup parent) {
        TextView text = new TextView(parent.getContext());
        text.setTextColor(ACCENT);
        text.setTextSize(17f);
        text.setPadding(dp(14), dp(16), dp(8), dp(8));
        text.setBackgroundColor(BG);
        return text;
    }

    private View buildInstallCard(ViewGroup parent) {
        LinearLayout root = new LinearLayout(parent.getContext());
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.TOP);
        root.setPadding(dp(12), dp(10), dp(10), dp(10));
        root.setMinimumHeight(dp(120));

        ImageView icon = new ImageView(parent.getContext());
        icon.setId(View.generateViewId());
        root.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout body = new LinearLayout(parent.getContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(10), 0, 0, 0);
        TextView title = new AutoMarqueeTextView(parent.getContext());
        title.setId(View.generateViewId());
        title.setTextColor(TEXT);
        title.setTextSize(16f);
        body.addView(title);
        TextView detail = new AutoMarqueeTextView(parent.getContext());
        detail.setId(View.generateViewId());
        detail.setTextColor(TEXT_SECONDARY);
        detail.setTextSize(12f);
        body.addView(detail);
        TextView time = new TextView(parent.getContext());
        time.setId(View.generateViewId());
        time.setTextColor(TEXT);
        time.setTextSize(13f);
        body.addView(time);
        TextView source = new AutoMarqueeTextView(parent.getContext());
        source.setId(View.generateViewId());
        source.setTextColor(TEXT_MUTED);
        source.setTextSize(10f);
        body.addView(source);
        root.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.setTag(new int[]{icon.getId(), title.getId(), detail.getId(), time.getId(), source.getId()});
        return root;
    }

    private static final class InstallHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView detail;
        final TextView time;
        final TextView source;
        InstallHolder(View item) {
            super(item);
            int[] ids = (int[]) item.getTag();
            icon = item.findViewById(ids[0]);
            title = item.findViewById(ids[1]);
            detail = item.findViewById(ids[2]);
            time = item.findViewById(ids[3]);
            source = item.findViewById(ids[4]);
        }
    }

    private final class HeatmapAdapter extends RecyclerView.Adapter<HeatmapHolder> {
        private final List<HeatmapDay> days;
        private long maxHourMs = 1L;
        HeatmapAdapter(List<HeatmapDay> days) {
            this.days = days;
            for (HeatmapDay day : days) for (long value : day.hours) maxHourMs = Math.max(maxHourMs, value);
        }
        @NonNull @Override public HeatmapHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new HeatmapHolder(buildHeatmapRow(parent));
        }
        @Override public void onBindViewHolder(@NonNull HeatmapHolder holder, int position) {
            HeatmapDay day = days.get(position);
            holder.date.setText(new SimpleDateFormat("MMM dd", Locale.getDefault()).format(new Date(day.dayMs)));
            holder.total.setText(verboseDuration(day.totalMs));
            for (int hour = 0; hour < 24; hour++) {
                long value = day.hours[hour];
                float ratio = Math.min(1f, value / (float) maxHourMs);
                int alpha = value <= 0L ? 24 : 45 + Math.round(210f * ratio);
                TextView cell = holder.cells[hour];
                cell.setBackgroundColor(Color.argb(alpha, 255, 92, 42));
                final int h = hour;
                cell.setOnClickListener(v -> Toast.makeText(AppUsageActivity.this,
                        String.format(Locale.getDefault(), "%02d:00–%02d:59 · %s", h, h,
                                verboseDuration(day.hours[h])), Toast.LENGTH_SHORT).show());
            }
        }
        @Override public int getItemCount() { return days.size(); }
    }

    private View buildHeatmapRow(ViewGroup parent) {
        LinearLayout root = new LinearLayout(parent.getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(12), dp(10), dp(10));

        LinearLayout heading = new LinearLayout(parent.getContext());
        heading.setOrientation(LinearLayout.HORIZONTAL);
        TextView date = new TextView(parent.getContext());
        date.setId(View.generateViewId());
        date.setTextColor(TEXT);
        date.setTextSize(15f);
        date.setTypeface(Typeface.DEFAULT_BOLD);
        heading.addView(date, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView total = new TextView(parent.getContext());
        total.setId(View.generateViewId());
        total.setTextColor(TEXT_SECONDARY);
        total.setTextSize(12f);
        heading.addView(total);
        root.addView(heading);

        LinearLayout heat = new LinearLayout(parent.getContext());
        heat.setOrientation(LinearLayout.HORIZONTAL);
        heat.setPadding(0, dp(7), 0, 0);
        int[] ids = new int[26];
        ids[0] = date.getId();
        ids[1] = total.getId();
        for (int i = 0; i < 24; i++) {
            TextView cell = new TextView(parent.getContext());
            cell.setId(View.generateViewId());
            cell.setGravity(Gravity.CENTER);
            cell.setText(i % 6 == 0 ? Integer.toString(i) : "");
            cell.setTextSize(7f);
            cell.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1f);
            lp.setMargins(dp(1), 0, dp(1), 0);
            heat.addView(cell, lp);
            ids[i + 2] = cell.getId();
        }
        root.addView(heat, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setTag(ids);
        return root;
    }

    private static final class HeatmapHolder extends RecyclerView.ViewHolder {
        final TextView date;
        final TextView total;
        final TextView[] cells = new TextView[24];
        HeatmapHolder(View item) {
            super(item);
            int[] ids = (int[]) item.getTag();
            date = item.findViewById(ids[0]);
            total = item.findViewById(ids[1]);
            for (int i = 0; i < 24; i++) cells[i] = item.findViewById(ids[i + 2]);
        }
    }

    private static final class Snapshot {
        final long sinceMs;
        final long untilMs;
        final List<AppUsageStore.TimelineEntry> exact;
        final List<AppUsageStore.TimelineEntry> daily;
        final List<AppUsageStore.DailyPhoneState> phone;
        final AppUsageStore.Summary summary;
        Snapshot(long sinceMs, long untilMs, List<AppUsageStore.TimelineEntry> exact,
                 List<AppUsageStore.TimelineEntry> daily,
                 List<AppUsageStore.DailyPhoneState> phone,
                 AppUsageStore.Summary summary) {
            this.sinceMs = sinceMs;
            this.untilMs = untilMs;
            this.exact = exact;
            this.daily = daily;
            this.phone = phone;
            this.summary = summary;
        }
    }

    private static final class TimelineRow {
        final boolean header;
        final boolean empty;
        final String label;
        final AppUsageStore.TimelineEntry entry;
        private TimelineRow(boolean header, boolean empty, String label,
                            AppUsageStore.TimelineEntry entry) {
            this.header = header;
            this.empty = empty;
            this.label = label;
            this.entry = entry;
        }
        static TimelineRow header(String label) { return new TimelineRow(true, false, label, null); }
        static TimelineRow event(AppUsageStore.TimelineEntry e) { return new TimelineRow(false, false, null, e); }
        static TimelineRow empty() { return new TimelineRow(false, true, null, null); }
    }

    private static final class InstallRow {
        final boolean header;
        final boolean empty;
        final String label;
        final AppUsageStore.TimelineEntry entry;
        private InstallRow(boolean header, boolean empty, String label,
                           AppUsageStore.TimelineEntry entry) {
            this.header = header;
            this.empty = empty;
            this.label = label;
            this.entry = entry;
        }
        static InstallRow header(String label) { return new InstallRow(true, false, label, null); }
        static InstallRow event(AppUsageStore.TimelineEntry e) { return new InstallRow(false, false, null, e); }
        static InstallRow empty() { return new InstallRow(false, true, null, null); }
    }

    private static final class AppAggregate {
        final String packageName;
        final String label;
        final boolean systemApp;
        long dailyUsageMs;
        long exactUsageMs;
        long longestSessionMs;
        long firstUsedMs = Long.MAX_VALUE;
        long lastUsedMs;
        int sessions;
        int interactions;
        int shortcuts;
        AppAggregate(String packageName, String label, boolean systemApp) {
            this.packageName = packageName;
            this.label = label;
            this.systemApp = systemApp;
        }
        long displayUsageMs() { return dailyUsageMs > 0L ? dailyUsageMs : exactUsageMs; }
    }

    private static final class HeatmapDay {
        final long dayMs;
        final long[] hours = new long[24];
        long totalMs;
        HeatmapDay(long dayMs) { this.dayMs = dayMs; }
    }
}
