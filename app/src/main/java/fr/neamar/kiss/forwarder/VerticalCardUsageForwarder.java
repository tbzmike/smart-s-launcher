package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.AppUsageTodayStore;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.result.Result;

/**
 * Adds Android UsageStats foreground time for today to app-backed Vertical Cards.
 *
 * UsageStats queries run only on a low-priority background worker. The visible usage value lives
 * in its own non-marquee TextView so launch-stat decoration can never overwrite it and so adding
 * usage does not make every card's continuously animated metadata line longer.
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
        // SmartCardListForwarder already rebuilt the card column. Reuse the in-memory snapshot;
        // never query Android UsageStats from a provider/history change. The rebuild viewport
        // controller already captured the pre-rebuild position, so this apply must not capture a
        // second (temporary) position while SmartCardListForwarder's legacy fullScroll is pending.
        resolveColumn();
        postApplySnapshot(false, true);
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
        pendingApplyFromDataSet = false;
        pendingApplyNeedsViewportProtection = false;
    }

    private boolean isEnabled() {
        return VERTICAL_CARDS.equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private void refreshSnapshotAsync() {
        if (destroyed || !isEnabled()) {
            snapshot = null;
            return;
        }
        if (!refreshInFlight.compareAndSet(false, true)) return;

        final android.content.Context appContext = mainActivity.getApplicationContext();
        usageExecutor.execute(() -> {
            AppUsageTodayStore.Snapshot fresh = AppUsageTodayStore.getToday(appContext);
            refreshInFlight.set(false);
            if (destroyed) return;
            snapshot = fresh;
            mainActivity.runOnUiThread(() -> postApplySnapshot(true, false));
        });
    }

    private void resolveColumn() {
        try {
            Field field = SmartCardListForwarder.class.getDeclaredField("column");
            field.setAccessible(true);
            Object value = field.get(smartCardListForwarder);
            ViewGroup resolved = value instanceof ViewGroup ? (ViewGroup) value : null;
            if (resolved != column) {
                if (column != null) column.removeCallbacks(applySnapshotRunnable);
                column = resolved;
            }
        } catch (ReflectiveOperationException ignored) {
            if (column != null) column.removeCallbacks(applySnapshotRunnable);
            column = null;
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
            String packageName = resolvePackage(result == null ? null : result.getPojo());
            if (TextUtils.isEmpty(packageName)) continue;

            UsageView usageResult = getOrCreateUsageView(wrapper);
            if (usageResult == null) continue;
            if (usageResult.created) layoutChanged = true;

            String usageText;
            if (!currentSnapshot.available) {
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

        TextView usage = new TextView(mainActivity);
        usage.setTag(USAGE_VIEW_TAG);
        usage.setTextColor(Color.argb(220, 255, 255, 255));
        usage.setTextSize(12f);
        usage.setGravity(Gravity.CENTER);
        usage.setSingleLine(true);
        usage.setEllipsize(TextUtils.TruncateAt.END);
        usage.setHorizontallyScrolling(false);
        usage.setFocusable(false);
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
