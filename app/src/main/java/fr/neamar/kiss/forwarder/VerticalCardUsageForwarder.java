package fr.neamar.kiss.forwarder;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.AppUsageTodayStore;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.AutoMarqueeTextView;

/** Adds Android UsageStats foreground time for today to each app-backed Vertical Card. */
final class VerticalCardUsageForwarder extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final String USAGE_MARKER = "  •  Used today: ";

    private final SmartCardListForwarder smartCardListForwarder;
    private ViewGroup column;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private AppUsageTodayStore.Snapshot snapshot;

    VerticalCardUsageForwarder(MainActivity activity, SmartCardListForwarder smartCardListForwarder) {
        super(activity);
        this.smartCardListForwarder = smartCardListForwarder;
    }

    void onCreate() {
        refreshSnapshotAndAttach();
    }

    void onResume() {
        // This is intentionally lightweight and does not rebuild history. Returning Home should
        // update only the usage text because the foreground time may have changed while an app was open.
        refreshSnapshotAndAttach();
    }

    void onDataSetChanged() {
        refreshSnapshotAndAttach();
    }

    void onConfigurationChanged() {
        refreshSnapshotAndAttach();
    }

    void onDestroy() {
        detach();
        column = null;
        snapshot = null;
    }

    private boolean isEnabled() {
        return VERTICAL_CARDS.equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private void refreshSnapshotAndAttach() {
        if (!isEnabled()) {
            detach();
            snapshot = null;
            return;
        }
        snapshot = AppUsageTodayStore.getToday(mainActivity);
        resolveColumn();
        attach();
        if (column != null) column.post(this::apply);
    }

    private void resolveColumn() {
        try {
            Field field = SmartCardListForwarder.class.getDeclaredField("column");
            field.setAccessible(true);
            Object value = field.get(smartCardListForwarder);
            ViewGroup newColumn = value instanceof ViewGroup ? (ViewGroup) value : null;
            if (newColumn != column) {
                detach();
                column = newColumn;
            }
        } catch (ReflectiveOperationException ignored) {
            detach();
            column = null;
        }
    }

    private void attach() {
        if (column == null || layoutListener != null) return;
        layoutListener = this::apply;
        column.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    private void detach() {
        if (column != null && layoutListener != null) {
            ViewTreeObserver observer = column.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
        }
        layoutListener = null;
    }

    private void apply() {
        if (!isEnabled() || column == null || snapshot == null || mainActivity.adapter == null) return;
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
            if (!snapshot.available) {
                usageText = "unavailable";
            } else {
                Long foregroundMs = snapshot.foregroundMsByPackage.get(packageName);
                usageText = formatDuration(foregroundMs == null ? 0L : foregroundMs);
            }

            String updated = current + USAGE_MARKER + usageText;
            if (!TextUtils.equals(strip.getText(), updated)) strip.setText(updated);
            strip.setContentDescription(updated);
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
