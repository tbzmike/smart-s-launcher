package fr.neamar.kiss.forwarder;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

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
import fr.neamar.kiss.ui.AutoMarqueeTextView;

/**
 * Adds Android UsageStats foreground time for today to app-backed Vertical Cards.
 *
 * UsageStats queries can be expensive and must never run in scrolling/layout callbacks. A single
 * background worker refreshes the snapshot when the launcher becomes active; ordinary dataset
 * changes only reuse the last snapshot already in memory.
 */
final class VerticalCardUsageForwarder extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final String USAGE_MARKER = "  •  Used today: ";

    private final SmartCardListForwarder smartCardListForwarder;
    private final ExecutorService usageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "smart-s-usage-stats");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

    private ViewGroup column;
    private volatile AppUsageTodayStore.Snapshot snapshot;
    private volatile boolean destroyed;

    VerticalCardUsageForwarder(MainActivity activity, SmartCardListForwarder smartCardListForwarder) {
        super(activity);
        this.smartCardListForwarder = smartCardListForwarder;
    }

    void onCreate() {
        resolveColumn();
        refreshSnapshotAsync();
    }

    void onResume() {
        // Usage changes while another app is foreground. Refresh asynchronously and update only
        // the existing metadata text when the snapshot arrives; never rebuild Home.
        resolveColumn();
        refreshSnapshotAsync();
    }

    void onDataSetChanged() {
        // A provider/history update must stay cheap. SmartCardListForwarder has already rebuilt the
        // column synchronously, so reuse the cached snapshot and apply once on the next UI turn.
        resolveColumn();
        postApplySnapshot();
    }

    void onConfigurationChanged() {
        resolveColumn();
        postApplySnapshot();
    }

    void onDestroy() {
        destroyed = true;
        usageExecutor.shutdownNow();
        column = null;
        snapshot = null;
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
        if (!refreshInFlight.compareAndSet(false, true)) {
            return;
        }

        final android.content.Context appContext = mainActivity.getApplicationContext();
        usageExecutor.execute(() -> {
            AppUsageTodayStore.Snapshot fresh = AppUsageTodayStore.getToday(appContext);
            refreshInFlight.set(false);
            if (destroyed) return;
            snapshot = fresh;
            mainActivity.runOnUiThread(this::postApplySnapshot);
        });
    }

    private void resolveColumn() {
        if (column != null) return;
        try {
            Field field = SmartCardListForwarder.class.getDeclaredField("column");
            field.setAccessible(true);
            Object value = field.get(smartCardListForwarder);
            column = value instanceof ViewGroup ? (ViewGroup) value : null;
        } catch (ReflectiveOperationException ignored) {
            column = null;
        }
    }

    private void postApplySnapshot() {
        if (destroyed || column == null || snapshot == null || !isEnabled()) return;
        column.removeCallbacks(this::applySnapshot);
        column.post(this::applySnapshot);
    }

    private void applySnapshot() {
        AppUsageTodayStore.Snapshot currentSnapshot = snapshot;
        if (destroyed || !isEnabled() || column == null || currentSnapshot == null
                || mainActivity.adapter == null) {
            return;
        }

        int count = Math.min(column.getChildCount(), mainActivity.adapter.getCount());
        for (int position = 0; position < count; position++) {
            View wrapper = column.getChildAt(position);
            Result<?> result = mainActivity.adapter.getItem(position);
            String packageName = resolvePackage(result == null ? null : result.getPojo());
            if (TextUtils.isEmpty(packageName)) continue;

            AutoMarqueeTextView strip = findStatsStrip(wrapper);
            if (strip == null) continue;

            String current = strip.getText() == null ? "" : strip.getText().toString();
            int marker = current.indexOf(USAGE_MARKER);
            if (marker >= 0) current = current.substring(0, marker);

            String usageText;
            if (!currentSnapshot.available) {
                usageText = "unavailable";
            } else {
                Long foregroundMs = currentSnapshot.foregroundMsByPackage.get(packageName);
                usageText = formatDuration(foregroundMs == null ? 0L : foregroundMs);
            }

            String updated = current + USAGE_MARKER + usageText;
            if (!TextUtils.equals(strip.getText(), updated)) {
                strip.setText(updated);
                strip.setContentDescription(updated);
            }
        }
    }

    private AutoMarqueeTextView findStatsStrip(View wrapper) {
        if (!(wrapper instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) wrapper;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child instanceof AutoMarqueeTextView) return (AutoMarqueeTextView) child;
        }
        return null;
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
}
