package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.AppUsageTodayStore;
import fr.neamar.kiss.db.HistoryItemUsageTodayStore;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.AutoMarqueeTextView;

/**
 * Adds Android UsageStats foreground time for today to app-backed Vertical Cards.
 *
 * Parent app cards use the complete package foreground total. Shortcut cards use an isolated
 * history-item duration, so a feature such as Reels or Shorts does not simply repeat the parent
 * Facebook/YouTube total.
 *
 * UsageStats queries run only on a low-priority background worker. The visible usage value lives
 * in its own TextView so launch-stat decoration can never overwrite it. It auto-scrolls only when
 * the usage label is wider than the card.
 */
final class VerticalCardUsageForwarder extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final String USAGE_VIEW_TAG = "smart-s-used-today";

    private final SmartCardListForwarder smartCardListForwarder;
    private final VerticalCardViewportController viewportController;
    private final ExecutorService usageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "smart-s-usage-stats");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private final Runnable applySnapshotRunnable;

    private ViewGroup column;
    private volatile AppUsageTodayStore.Snapshot snapshot;
    private volatile HistoryItemUsageTodayStore.Snapshot shortcutSnapshot;
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
        // SmartCardListForwarder already rebuilt the card column. Reuse the in-memory snapshots
        // unless the set of shortcut history items changed. This keeps UsageEvents work out of the
        // normal search/provider refresh path while still loading feature durations when shortcuts
        // first appear after startup.
        resolveColumn();
        Map<String, String> currentShortcutTargets = collectShortcutTargets();
        if (!currentShortcutTargets.equals(loadedShortcutTargets)) {
            refreshSnapshotAsync(currentShortcutTargets);
        } else {
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
            String packageName = resolvePackage(pojo);
            if (TextUtils.isEmpty(packageName)) continue;

            UsageView usageResult = getOrCreateUsageView(wrapper);
            if (usageResult == null) continue;
            if (usageResult.created) layoutChanged = true;

            String usageText;
            if (pojo instanceof ShortcutPojo) {
                if (currentShortcutSnapshot == null || !currentShortcutSnapshot.available) {
                    usageText = "Used today: unavailable";
                } else {
                    Long foregroundMs = currentShortcutSnapshot.foregroundMsByHistoryId.get(
                            pojo.getHistoryId());
                    usageText = "Used today: " + formatDuration(
                            foregroundMs == null ? 0L : foregroundMs);
                }
            } else if (!currentSnapshot.available) {
                usageText = "Used today: unavailable";
            } else {
                Long foregroundMs = currentSnapshot.foregroundMsByPackage.get(packageName);
                usageText = "Used today: " + formatDuration(
                        foregroundMs == null ? 0L : foregroundMs);
            }

            if (!TextUtils.equals(usageResult.view.getText(), usageText)) {
                usageResult.view.setText(usageText);
                layoutChanged = true;
            }
            usageResult.view.setContentDescription(usageText);
        }

        if (protectViewport && layoutChanged) {
            viewportController.restoreAfterContentMutation(viewport);
        }
    }

    private UsageView getOrCreateUsageView(View wrapper) {
        if (!(wrapper instanceof LinearLayout)) return null;
        LinearLayout group = (LinearLayout) wrapper;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && USAGE_VIEW_TAG.equals(child.getTag())) {
                return new UsageView((TextView) child, false);
            }
        }

        AutoMarqueeTextView usage = new AutoMarqueeTextView(mainActivity);
        usage.setTag(USAGE_VIEW_TAG);
        usage.setTextColor(Color.argb(220, 255, 255, 255));
        usage.setTextSize(12f);
        usage.setGravity(Gravity.CENTER);
        usage.setClickable(false);
        usage.setPadding(dp(8), 0, dp(8), dp(5));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(10), 0, dp(10), 0);
        group.addView(usage, lp);
        return new UsageView(usage, true);
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

    private String formatDuration(long foregroundMs) {
        if (foregroundMs <= 0L) return "0m";
        long totalMinutes = foregroundMs / 60000L;
        if (totalMinutes == 0L) return "<1m";
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours == 0L) return minutes + "m";
        if (minutes == 0L) return hours + "h";
        return hours + "h " + minutes + "m";
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
