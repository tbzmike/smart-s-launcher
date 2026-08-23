package fr.neamar.kiss.forwarder;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;

import java.lang.reflect.Field;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.result.Result;

/**
 * Makes notification-history behavior explicit on the custom Vertical Cards renderer.
 *
 * SmartCardListForwarder builds its own card hierarchy, so relying only on ListView/RecordAdapter
 * routing is not enough. This bridge runs after each Vertical Cards rebuild and guarantees that:
 *  - long-press on a card with saved history opens that app's saved history first;
 *  - notification-result cards open saved history when tapped;
 *  - action buttons (for example Mark read) keep their own click listeners.
 */
final class VerticalCardNotificationHistoryForwarder extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";

    private final SmartCardListForwarder smartCardListForwarder;
    private ViewGroup column;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;

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
    void onDestroy() { detach(); column = null; }

    private boolean isEnabled() {
        return VERTICAL_CARDS.equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private void refresh() {
        if (!isEnabled()) {
            detach();
            return;
        }
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
