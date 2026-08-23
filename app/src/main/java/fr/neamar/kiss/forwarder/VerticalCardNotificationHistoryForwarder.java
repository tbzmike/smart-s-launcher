package fr.neamar.kiss.forwarder;

import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.LaunchHistoryStatsStore;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.AutoMarqueeTextView;

/**
 * Makes notification-history behavior explicit on the custom Vertical Cards renderer and enriches
 * the existing between-card label with launch activity from the KISS history table.
 *
 * SmartCardListForwarder builds its own card hierarchy, so relying only on ListView/RecordAdapter
 * routing is not enough. This bridge runs after each Vertical Cards rebuild and guarantees that:
 *  - long-press on a card with saved history opens that app's saved history first;
 *  - notification-result cards open saved history when tapped;
 *  - action buttons (for example Mark read) keep their own click listeners;
 *  - the strip below each card shows app/shortcut name, last launch time and launches today.
 */
final class VerticalCardNotificationHistoryForwarder extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final String STATS_MARKER = "  •  Last: ";

    private final SmartCardListForwarder smartCardListForwarder;
    private ViewGroup column;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private Map<String, LaunchHistoryStatsStore.Stats> launchStats = Collections.emptyMap();

    VerticalCardNotificationHistoryForwarder(MainActivity activity,
                                             SmartCardListForwarder smartCardListForwarder) {
        super(activity);
        this.smartCardListForwarder = smartCardListForwarder;
    }

    void onCreate() { refresh(); }
    void onResume() { refresh(); }
    void onDataSetChanged() { refresh(); }
    void onConfigurationChanged() { refresh(); }

    void onPause() { detach(); }
    void onDestroy() {
        detach();
        column = null;
        launchStats = Collections.emptyMap();
    }

    private boolean isEnabled() {
        return VERTICAL_CARDS.equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private void refresh() {
        if (!isEnabled()) {
            detach();
            launchStats = Collections.emptyMap();
            return;
        }
        launchStats = LaunchHistoryStatsStore.getAll(mainActivity);
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
        if (!isEnabled() || column == null || mainActivity.adapter == null) return;
        int count = Math.min(column.getChildCount(), mainActivity.adapter.getCount());
        for (int position = 0; position < count; position++) {
            View wrapper = column.getChildAt(position);
            Result<?> result = mainActivity.adapter.getItem(position);
            final int adapterPosition = position;

            applyLaunchStats(wrapper, result);

            View.OnLongClickListener historyFirstLongPress = v -> {
                if (mainActivity.adapter.showNotificationHistoryIfAvailable(adapterPosition, v)) {
                    return true;
                }
                mainActivity.adapter.onLongClick(adapterPosition, v);
                return true;
            };
            applyLongPressRecursively(wrapper, historyFirstLongPress);

            // A NotificationPojo is itself a notification tile. Tapping anywhere on its visual
            // surface must use RecordAdapter.onClick(), whose first action is saved app history.
            if (result.getPojo() instanceof NotificationPojo) {
                View.OnClickListener notificationClick = v ->
                        mainActivity.adapter.onClick(adapterPosition, v);
                applyNotificationClickRecursively(wrapper, notificationClick);
            }
        }
    }

    private void applyLaunchStats(View wrapper, Result<?> result) {
        if (!(wrapper instanceof ViewGroup) || result == null || result.getPojo() == null) return;
        ViewGroup group = (ViewGroup) wrapper;
        AutoMarqueeTextView strip = null;

        // SmartCardListForwarder deliberately places the between-card name strip as a direct child
        // of the wrapper after the card. Reuse that exact space instead of adding card height.
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child instanceof AutoMarqueeTextView) {
                strip = (AutoMarqueeTextView) child;
                break;
            }
        }
        if (strip == null) return;

        String historyId = result.getPojo().getHistoryId();
        LaunchHistoryStatsStore.Stats stats = launchStats.get(historyId);

        // Preserve SmartCardListForwarder's cleaned display label. On later global-layout passes the
        // strip already contains stats, so take only the stable name prefix to avoid duplication.
        String currentText = strip.getText() == null ? "" : strip.getText().toString().trim();
        int marker = currentText.indexOf(STATS_MARKER);
        String appName = marker > 0 ? currentText.substring(0, marker).trim() : currentText;
        if (appName.isEmpty()) {
            appName = result.getPojo().getName();
            if (appName == null || appName.trim().isEmpty()) appName = "App";
        }

        String last;
        int today = 0;
        if (stats == null || stats.lastLaunchTime <= 0L) {
            last = "Never";
        } else {
            last = DateFormat.getTimeFormat(mainActivity).format(new Date(stats.lastLaunchTime));
            today = stats.launchesToday;
        }
        String times = today == 1 ? "1 time today" : today + " times today";
        String summary = appName + STATS_MARKER + last + "  •  Launched: " + times;
        if (!TextUtils.equals(strip.getText(), summary)) {
            strip.setText(summary);
        }
        strip.setContentDescription(appName + ", last launched " + last + ", launched " + times);
    }

    private void applyLongPressRecursively(View view, View.OnLongClickListener listener) {
        if (view == null || view instanceof Button) return;
        view.setLongClickable(true);
        view.setOnLongClickListener(listener);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyLongPressRecursively(group.getChildAt(i), listener);
        }
    }

    private void applyNotificationClickRecursively(View view, View.OnClickListener listener) {
        if (view == null || view instanceof Button) return;
        view.setClickable(true);
        view.setOnClickListener(listener);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyNotificationClickRecursively(group.getChildAt(i), listener);
        }
    }
}
