from pathlib import Path
import subprocess


def blob(path: str) -> str:
    return subprocess.check_output(["git", "hash-object", path], text=True).strip()


def require_blob(path: str, expected: str) -> None:
    actual = blob(path)
    if actual != expected:
        raise SystemExit(f"{path}: expected blob {expected}, found {actual}")


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one guarded match, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Refuse to patch anything except the exact latest-green 3.30.56 application sources inspected
# before this repair branch was created.
require_blob("app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java",
             "3d560ddb70ddc31c136dc3370344a8a0d1eae516")
require_blob("app/src/main/java/fr/neamar/kiss/result/Result.java",
             "7b2c8e7431a817a4dfa12278d0265c6c444bc8cc")
require_blob("app/build.gradle", "9571be806098511d2ca349527cffe7ff3d31911f")

counter = Path("app/src/main/java/fr/neamar/kiss/ui/TileLaunchCounter.java")
if counter.exists():
    raise SystemExit(f"{counter}: refusing to overwrite an existing source file")
counter.write_text(r'''package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;

/** Persistent click count for exact launcher tiles that are not represented by normal history. */
public final class TileLaunchCounter {
    private static final String PREFS = "smart-s-tile-launch-counts";
    private static final String COUNT_PREFIX = "count:";
    private static final Object LOCK = new Object();

    private TileLaunchCounter() {}

    /** Record one explicit click on an exact tile. */
    public static void record(@NonNull Context context, @NonNull Pojo pojo) {
        String key = storageKey(pojo);
        if (TextUtils.isEmpty(key)) return;
        synchronized (LOCK) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String countKey = COUNT_PREFIX + key;
            long current = Math.max(0L, prefs.getLong(countKey, 0L));
            long next = current == Long.MAX_VALUE ? Long.MAX_VALUE : current + 1L;
            prefs.edit().putLong(countKey, next).apply();
        }
    }

    /** Return the number of clicks recorded for this exact tile since tracking became available. */
    public static long getTotal(@NonNull Context context, @NonNull Pojo pojo) {
        String key = storageKey(pojo);
        if (TextUtils.isEmpty(key)) return 0L;
        synchronized (LOCK) {
            return Math.max(0L, context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(COUNT_PREFIX + key, 0L));
        }
    }

    /**
     * A notification ID can be reused by an app. Including post time keeps a newly posted
     * notification from inheriting the click count of an older notification with the same ID.
     */
    @NonNull
    static String storageKey(@NonNull Pojo pojo) {
        if (pojo instanceof NotificationPojo) {
            NotificationPojo notification = (NotificationPojo) pojo;
            return notification.id + "|post:" + Math.max(0L, notification.postTime);
        }
        String historyId = pojo.getHistoryId();
        return historyId == null ? "" : historyId;
    }
}
''', encoding="utf-8")

usage_forwarder = Path(
    "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java")
usage_forwarder.write_text(r'''package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.AppUsageTodayStore;
import fr.neamar.kiss.db.HistoryItemUsageTodayStore;
import fr.neamar.kiss.db.LaunchStatsProvider;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.SmartTextAppearance;
import fr.neamar.kiss.ui.TileLaunchCounter;

/**
 * Adds complete history metadata below every Vertical Card.
 *
 * The metadata is deliberately a sibling below the rounded card, never content inside it. It uses
 * real stored history timestamps/counts, Android UsageStats for app-backed cards, exact notification
 * post times, and an exact per-notification click counter. Expensive database/UsageStats reads stay
 * on one low-priority worker and never run once per visible card.
 */
final class VerticalCardUsageForwarder extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    // Keep the existing tag so the old partial "Used today" line is upgraded in place.
    private static final String USAGE_VIEW_TAG = "smart-s-used-today";

    private final SmartCardListForwarder smartCardListForwarder;
    private final VerticalCardViewportController viewportController;
    private final ExecutorService usageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "smart-s-usage-stats");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean statsRefreshInFlight = new AtomicBoolean(false);
    private final Runnable applySnapshotRunnable;

    private ViewGroup column;
    private volatile AppUsageTodayStore.Snapshot snapshot;
    private volatile HistoryItemUsageTodayStore.Snapshot shortcutSnapshot;
    private volatile Map<String, LaunchStatsProvider.LaunchStats> launchStats = Collections.emptyMap();
    private Map<String, String> loadedShortcutTargets = Collections.emptyMap();
    private Map<String, String> pendingShortcutTargets = Collections.emptyMap();
    private boolean refreshRequested;
    private volatile boolean destroyed;
    private boolean pendingApplyFromDataSet;
    private boolean pendingApplyNeedsViewportProtection;

    VerticalCardUsageForwarder(MainActivity activity,
                               SmartCardListForwarder smartCardListForwarder,
                               VerticalCardViewportController viewportController) {
        super(activity);
        this.smartCardListForwarder = smartCardListForwarder;
        this.viewportController = viewportController;
        this.applySnapshotRunnable = this::applySnapshot;
    }

    void onCreate() {
        resolveColumn();
        refreshSnapshotAsync();
    }

    void onResume() {
        resolveColumn();
        refreshSnapshotAsync();
    }

    void onDataSetChanged() {
        // SmartCardListForwarder has already rebuilt the card column. Refresh the lightweight
        // grouped launch statistics on every history change so "Posted" and "Launched" update
        // immediately, while reusing UsageStats unless the set of shortcut targets changed.
        resolveColumn();
        Map<String, String> currentShortcutTargets = collectShortcutTargets();
        if (!currentShortcutTargets.equals(loadedShortcutTargets)) {
            refreshSnapshotAsync(currentShortcutTargets);
        } else {
            refreshLaunchStatsAsync();
            postApplySnapshot(false, true);
        }
    }

    void onConfigurationChanged() {
        resolveColumn();
        postApplySnapshot(false, true);
    }

    void onDestroy() {
        destroyed = true;
        usageExecutor.shutdownNow();
        if (column != null) column.removeCallbacks(applySnapshotRunnable);
        column = null;
        snapshot = null;
        shortcutSnapshot = null;
        launchStats = Collections.emptyMap();
        loadedShortcutTargets = Collections.emptyMap();
        pendingShortcutTargets = Collections.emptyMap();
        refreshRequested = false;
        pendingApplyFromDataSet = false;
        pendingApplyNeedsViewportProtection = false;
    }

    private boolean isEnabled() {
        return VERTICAL_CARDS.equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private void refreshSnapshotAsync() {
        refreshSnapshotAsync(collectShortcutTargets());
    }

    private void refreshSnapshotAsync(Map<String, String> shortcutTargets) {
        if (destroyed || !isEnabled()) {
            snapshot = null;
            shortcutSnapshot = null;
            launchStats = Collections.emptyMap();
            return;
        }

        Map<String, String> requestedTargets = Collections.unmodifiableMap(
                new HashMap<>(shortcutTargets));
        if (!refreshInFlight.compareAndSet(false, true)) {
            pendingShortcutTargets = requestedTargets;
            refreshRequested = true;
            return;
        }

        final android.content.Context appContext = mainActivity.getApplicationContext();
        usageExecutor.execute(() -> {
            Map<String, LaunchStatsProvider.LaunchStats> freshStats = loadLaunchStats(appContext);
            AppUsageTodayStore.Snapshot fresh = AppUsageTodayStore.getToday(appContext);
            HistoryItemUsageTodayStore.Snapshot freshShortcuts =
                    HistoryItemUsageTodayStore.getToday(
                            appContext, requestedTargets, fresh.available);
            if (destroyed) {
                refreshInFlight.set(false);
                return;
            }
            mainActivity.runOnUiThread(() -> {
                refreshInFlight.set(false);
                if (destroyed) return;
                launchStats = freshStats;
                snapshot = fresh;
                shortcutSnapshot = freshShortcuts;
                loadedShortcutTargets = requestedTargets;
                postApplySnapshot(true, false);

                if (refreshRequested) {
                    refreshRequested = false;
                    Map<String, String> pending = pendingShortcutTargets;
                    pendingShortcutTargets = Collections.emptyMap();
                    if (!pending.equals(loadedShortcutTargets)) {
                        refreshSnapshotAsync(pending);
                    }
                }
            });
        });
    }

    private void refreshLaunchStatsAsync() {
        if (destroyed || !isEnabled() || !statsRefreshInFlight.compareAndSet(false, true)) return;
        final android.content.Context appContext = mainActivity.getApplicationContext();
        usageExecutor.execute(() -> {
            Map<String, LaunchStatsProvider.LaunchStats> freshStats = loadLaunchStats(appContext);
            if (destroyed) {
                statsRefreshInFlight.set(false);
                return;
            }
            mainActivity.runOnUiThread(() -> {
                statsRefreshInFlight.set(false);
                if (destroyed) return;
                launchStats = freshStats;
                postApplySnapshot(false, true);
            });
        });
    }

    private Map<String, LaunchStatsProvider.LaunchStats> loadLaunchStats(
            android.content.Context context) {
        try {
            return LaunchStatsProvider.loadAll(context);
        } catch (RuntimeException ignored) {
            // Never invent launch metadata if the history database cannot be read.
            return Collections.emptyMap();
        }
    }

    private Map<String, String> collectShortcutTargets() {
        if (mainActivity.adapter == null) return Collections.emptyMap();
        HashMap<String, String> targets = new HashMap<>();
        for (int position = 0; position < mainActivity.adapter.getCount(); position++) {
            Result<?> result = mainActivity.adapter.getItem(position);
            if (result == null || !(result.getPojo() instanceof ShortcutPojo)) continue;
            ShortcutPojo shortcut = (ShortcutPojo) result.getPojo();
            String packageName = resolvePackage(shortcut);
            if (!TextUtils.isEmpty(packageName)) {
                targets.put(shortcut.getHistoryId(), packageName);
            }
        }
        return targets;
    }

    private void resolveColumn() {
        ViewGroup resolved = smartCardListForwarder.getColumn();
        if (resolved != column) {
            if (column != null) column.removeCallbacks(applySnapshotRunnable);
            column = resolved;
        }
    }

    private void postApplySnapshot(boolean protectViewport, boolean fromDataSet) {
        if (destroyed || column == null || snapshot == null || !isEnabled()) return;
        pendingApplyNeedsViewportProtection |= protectViewport;
        pendingApplyFromDataSet |= fromDataSet;
        column.removeCallbacks(applySnapshotRunnable);
        column.post(applySnapshotRunnable);
    }

    private void applySnapshot() {
        AppUsageTodayStore.Snapshot currentSnapshot = snapshot;
        HistoryItemUsageTodayStore.Snapshot currentShortcutSnapshot = shortcutSnapshot;
        Map<String, LaunchStatsProvider.LaunchStats> currentLaunchStats = launchStats;
        boolean fromDataSet = pendingApplyFromDataSet;
        boolean protectViewport = pendingApplyNeedsViewportProtection && !fromDataSet;
        pendingApplyFromDataSet = false;
        pendingApplyNeedsViewportProtection = false;

        if (destroyed || !isEnabled() || column == null || currentSnapshot == null
                || mainActivity.adapter == null) return;

        VerticalCardViewportController.ViewportSnapshot viewport = protectViewport
                ? viewportController.captureForContentMutation() : null;
        boolean layoutChanged = false;

        int count = Math.min(column.getChildCount(), mainActivity.adapter.getCount());
        for (int position = 0; position < count; position++) {
            View wrapper = column.getChildAt(position);
            Result<?> result = mainActivity.adapter.getItem(position);
            Pojo pojo = result == null ? null : result.getPojo();
            if (pojo == null) continue;

            UsageView metadataResult = getOrCreateUsageView(wrapper);
            if (metadataResult == null) continue;
            if (metadataResult.created) layoutChanged = true;

            LaunchStatsProvider.LaunchStats stats = currentLaunchStats.get(pojo.getHistoryId());
            String packageName = resolvePackage(pojo);
            String metadataText = buildMetadata(
                    pojo, packageName, stats, currentSnapshot, currentShortcutSnapshot);

            if (!TextUtils.equals(metadataResult.view.getText(), metadataText)) {
                metadataResult.view.setText(metadataText);
                layoutChanged = true;
            }
            metadataResult.view.setContentDescription(metadataText);
            metadataResult.view.setVisibility(View.VISIBLE);
            SmartTextAppearance.applyHistoryMetadata(metadataResult.view);
        }

        if (protectViewport && layoutChanged) {
            viewportController.restoreAfterContentMutation(viewport);
        }
    }

    private String buildMetadata(Pojo pojo,
                                 String packageName,
                                 LaunchStatsProvider.LaunchStats stats,
                                 AppUsageTodayStore.Snapshot currentSnapshot,
                                 HistoryItemUsageTodayStore.Snapshot currentShortcutSnapshot) {
        StringBuilder metadata = new StringBuilder();
        appendMetadata(metadata, formatPostedTime(pojo, stats));

        String usage = formatUsage(
                pojo, packageName, currentSnapshot, currentShortcutSnapshot);
        appendMetadata(metadata, usage);

        long launches = pojo instanceof NotificationPojo
                ? TileLaunchCounter.getTotal(mainActivity, pojo)
                : stats == null ? 0L : Math.max(0, stats.totalLaunches);
        appendMetadata(metadata, formatLaunchCount(launches));
        return metadata.toString();
    }

    private String formatPostedTime(Pojo pojo, LaunchStatsProvider.LaunchStats stats) {
        long timestamp = resolveTimestamp(pojo, stats);
        String label = pojo instanceof NotificationPojo ? "Received" : "Posted";
        if (timestamp <= 0L) return label + ": unavailable";

        long now = System.currentTimeMillis();
        String relative;
        if (Math.abs(now - timestamp) < DateUtils.MINUTE_IN_MILLIS) {
            relative = timestamp <= now ? "just now" : "in <1m";
        } else {
            relative = DateUtils.getRelativeTimeSpanString(
                    timestamp,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE).toString();
        }

        Date date = new Date(timestamp);
        String exact = DateFormat.getTimeFormat(mainActivity).format(date);
        if (!DateUtils.isToday(timestamp)) {
            exact = DateFormat.getMediumDateFormat(mainActivity).format(date) + " " + exact;
        }
        return label + " " + relative + " · " + exact;
    }

    private long resolveTimestamp(Pojo pojo, LaunchStatsProvider.LaunchStats stats) {
        if (pojo instanceof NotificationPojo) {
            long postTime = ((NotificationPojo) pojo).postTime;
            if (postTime > 0L) return postTime;
        }
        if (pojo instanceof CommunicationPojo) {
            long eventTime = ((CommunicationPojo) pojo).timestamp;
            if (eventTime > 0L) return eventTime;
        }
        return stats == null ? 0L : Math.max(0L, stats.lastLaunchTime);
    }

    private String formatUsage(Pojo pojo,
                               String packageName,
                               AppUsageTodayStore.Snapshot currentSnapshot,
                               HistoryItemUsageTodayStore.Snapshot currentShortcutSnapshot) {
        if (TextUtils.isEmpty(packageName)) return null;
        if (pojo instanceof ShortcutPojo) {
            if (currentShortcutSnapshot == null || !currentShortcutSnapshot.available) {
                return "Used today: unavailable";
            }
            Long foregroundMs = currentShortcutSnapshot.foregroundMsByHistoryId.get(
                    pojo.getHistoryId());
            return "Used today: " + formatDuration(foregroundMs == null ? 0L : foregroundMs);
        }
        if (!currentSnapshot.available) return "Used today: unavailable";
        Long foregroundMs = currentSnapshot.foregroundMsByPackage.get(packageName);
        return "Used today: " + formatDuration(foregroundMs == null ? 0L : foregroundMs);
    }

    private void appendMetadata(StringBuilder builder, String value) {
        if (TextUtils.isEmpty(value)) return;
        if (builder.length() > 0) builder.append("  •  ");
        builder.append(value);
    }

    private UsageView getOrCreateUsageView(View wrapper) {
        if (!(wrapper instanceof LinearLayout)) return null;
        LinearLayout group = (LinearLayout) wrapper;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && USAGE_VIEW_TAG.equals(child.getTag())) {
                TextView existing = (TextView) child;
                configureMetadataView(existing);
                return new UsageView(existing, false);
            }
        }

        TextView metadata = new TextView(mainActivity);
        metadata.setTag(USAGE_VIEW_TAG);
        metadata.setTextColor(Color.argb(220, 255, 255, 255));
        metadata.setTextSize(12f);
        configureMetadataView(metadata);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(10), dp(3), dp(10), 0);
        group.addView(metadata, lp);
        return new UsageView(metadata, true);
    }

    private void configureMetadataView(TextView metadata) {
        metadata.setGravity(Gravity.CENTER);
        metadata.setClickable(false);
        metadata.setFocusable(false);
        metadata.setSingleLine(false);
        metadata.setMaxLines(Integer.MAX_VALUE);
        metadata.setEllipsize(null);
        metadata.setHorizontallyScrolling(false);
        metadata.setSelected(false);
        metadata.setPadding(dp(8), 0, dp(8), dp(5));
    }

    private String resolvePackage(Pojo pojo) {
        if (pojo instanceof DisabledAppPojo) {
            return ((DisabledAppPojo) pojo).targetPackage;
        }
        if (pojo instanceof AppPojo) {
            return ((AppPojo) pojo).packageName;
        }
        if (pojo instanceof ShortcutPojo) {
            ShortcutPojo shortcut = (ShortcutPojo) pojo;
            return TextUtils.isEmpty(shortcut.targetPackage)
                    ? shortcut.packageName : shortcut.targetPackage;
        }
        if (pojo instanceof NotificationPojo) {
            return ((NotificationPojo) pojo).packageName;
        }
        return null;
    }

    static String formatDuration(long foregroundMs) {
        if (foregroundMs <= 0L) return "0m";
        long totalMinutes = foregroundMs / 60000L;
        if (totalMinutes == 0L) return "<1m";
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours == 0L) return minutes + "m";
        if (minutes == 0L) return hours + "h";
        return hours + "h " + minutes + "m";
    }

    static String formatLaunchCount(long count) {
        long safe = Math.max(0L, count);
        if (safe == 1L) return "Launched once";
        if (safe == 2L) return "Launched twice";
        return "Launched " + safe + " times";
    }

    private int dp(int value) {
        return Math.round(value * mainActivity.getResources().getDisplayMetrics().density);
    }

    private static final class UsageView {
        final TextView view;
        final boolean created;

        UsageView(TextView view, boolean created) {
            this.view = view;
            this.created = created;
        }
    }
}
''', encoding="utf-8")

replace_once(
    "app/src/main/java/fr/neamar/kiss/result/Result.java",
    "import fr.neamar.kiss.pojo.DisabledAppPojo;\nimport fr.neamar.kiss.pojo.PhonePojo;",
    "import fr.neamar.kiss.pojo.DisabledAppPojo;\nimport fr.neamar.kiss.pojo.NotificationPojo;\nimport fr.neamar.kiss.pojo.PhonePojo;")
replace_once(
    "app/src/main/java/fr/neamar/kiss/result/Result.java",
    "import fr.neamar.kiss.ui.ListPopup;\nimport fr.neamar.kiss.ui.UniversalHistoryTimestamp;",
    "import fr.neamar.kiss.ui.ListPopup;\nimport fr.neamar.kiss.ui.TileLaunchCounter;\nimport fr.neamar.kiss.ui.UniversalHistoryTimestamp;")
replace_once(
    "app/src/main/java/fr/neamar/kiss/result/Result.java",
    "    protected final void recordLaunch(Context context, @Nullable QueryInterface queryInterface) {\n"
    "        // Save in history\n"
    "        if (canAddToHistory()) {\n",
    "    protected final void recordLaunch(Context context, @Nullable QueryInterface queryInterface) {\n"
    "        // Notification tiles intentionally stay out of normal launch history: notification\n"
    "        // arrivals and notification clicks are different facts. Count the explicit card click\n"
    "        // separately so Vertical Cards can report it without corrupting history statistics.\n"
    "        if (pojo instanceof NotificationPojo) {\n"
    "            TileLaunchCounter.record(context, pojo);\n"
    "        }\n"
    "\n"
    "        // Save successful normal launches in history.\n"
    "        if (canAddToHistory()) {\n")

replace_once(
    "app/build.gradle",
    "        // Smart S Launcher 3.30.56 - verified build version correction\n"
    "        versionCode 484\n"
    "        versionName \"3.30.56\"",
    "        // Smart S Launcher 3.30.57 - complete Vertical Cards timestamps and launch metadata\n"
    "        versionCode 485\n"
    "        versionName \"3.30.57\"")

test_path = Path(
    "app/src/test/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarderTest.java")
if test_path.exists():
    raise SystemExit(f"{test_path}: refusing to overwrite an existing test")
test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text(r'''package fr.neamar.kiss.forwarder;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VerticalCardUsageForwarderTest {
    @Test
    public void formatsUsageDurationsWithoutDroppingMinutes() {
        assertEquals("0m", VerticalCardUsageForwarder.formatDuration(0L));
        assertEquals("<1m", VerticalCardUsageForwarder.formatDuration(30_000L));
        assertEquals("49m", VerticalCardUsageForwarder.formatDuration(49L * 60_000L));
        assertEquals("1h 31m", VerticalCardUsageForwarder.formatDuration(91L * 60_000L));
    }

    @Test
    public void formatsHumanLaunchCounts() {
        assertEquals("Launched 0 times", VerticalCardUsageForwarder.formatLaunchCount(0));
        assertEquals("Launched once", VerticalCardUsageForwarder.formatLaunchCount(1));
        assertEquals("Launched twice", VerticalCardUsageForwarder.formatLaunchCount(2));
        assertEquals("Launched 7 times", VerticalCardUsageForwarder.formatLaunchCount(7));
    }
}
''', encoding="utf-8")

# Final source-level invariants. These are deliberately simple and explicit so the patch cannot
# silently degrade into another partial "Used today" implementation.
vertical = usage_forwarder.read_text(encoding="utf-8")
required = [
    'USAGE_VIEW_TAG = "smart-s-used-today"',
    'label = pojo instanceof NotificationPojo ? "Received" : "Posted"',
    'DateUtils.getRelativeTimeSpanString',
    'DateFormat.getTimeFormat(mainActivity)',
    'return "Used today: " + formatDuration',
    'TileLaunchCounter.getTotal(mainActivity, pojo)',
    'return "Launched twice"',
    'metadata.setSingleLine(false)',
    'metadata.setEllipsize(null)',
]
for needle in required:
    if needle not in vertical:
        raise SystemExit(f"VerticalCardUsageForwarder.java: missing invariant: {needle}")

result_text = Path("app/src/main/java/fr/neamar/kiss/result/Result.java").read_text(encoding="utf-8")
if result_text.count("TileLaunchCounter.record(context, pojo);") != 1:
    raise SystemExit("Result.java: notification click tracking is not wired exactly once")

print("Verified 3.30.57 Vertical Cards timestamp patch prepared successfully")
