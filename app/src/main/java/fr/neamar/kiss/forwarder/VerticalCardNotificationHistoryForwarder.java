package fr.neamar.kiss.forwarder;

import android.graphics.Rect;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.LaunchHistoryStatsStore;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.AutoMarqueeTextView;

/**
 * Makes notification-history behavior explicit on the custom Vertical Cards renderer and enriches
 * the existing between-card label with launch activity from the KISS history table.
 *
 * Decoration is applied only when cards are created/rebuilt. It must never run from a permanent
 * global-layout listener because that would recursively walk every card during ordinary layouts
 * and scrolling, and it could overwrite independent metadata such as today's usage time.
 */
final class VerticalCardNotificationHistoryForwarder extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final String STATS_MARKER = "  •  Last: ";
    private static final float BOTTOM_SWIPE_THRESHOLD_DP = 28f;
    private static final float BOTTOM_SWIPE_AXIS_BIAS = 1.15f;

    private final SmartCardListForwarder smartCardListForwarder;
    private ViewGroup column;
    private ScrollView scroller;
    private Map<String, LaunchHistoryStatsStore.Stats> launchStats = Collections.emptyMap();

    private float bottomSwipeDownRawX;
    private float bottomSwipeDownRawY;
    private boolean bottomSwipeStartedOnCard;
    private boolean bottomSwipeTriggered;

    VerticalCardNotificationHistoryForwarder(MainActivity activity,
                                             SmartCardListForwarder smartCardListForwarder) {
        super(activity);
        this.smartCardListForwarder = smartCardListForwarder;
    }

    void onCreate() { refresh(); }
    void onResume() { refresh(); }
    void onDataSetChanged() { refresh(); }
    void onConfigurationChanged() { refresh(); }

    void onPause() { resetBottomSwipe(); }
    void onDestroy() {
        resetBottomSwipe();
        column = null;
        scroller = null;
        launchStats = Collections.emptyMap();
    }

    private boolean isEnabled() {
        return VERTICAL_CARDS.equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private void refresh() {
        if (!isEnabled()) {
            launchStats = Collections.emptyMap();
            column = null;
            scroller = null;
            resetBottomSwipe();
            return;
        }
        launchStats = LaunchHistoryStatsStore.getAll(mainActivity);
        resolveViews();
        if (column != null) column.post(this::apply);
    }

    private void resolveViews() {
        try {
            Field columnField = SmartCardListForwarder.class.getDeclaredField("column");
            columnField.setAccessible(true);
            Object columnValue = columnField.get(smartCardListForwarder);
            column = columnValue instanceof ViewGroup ? (ViewGroup) columnValue : null;

            Field scrollerField = SmartCardListForwarder.class.getDeclaredField("scroller");
            scrollerField.setAccessible(true);
            Object scrollerValue = scrollerField.get(smartCardListForwarder);
            scroller = scrollerValue instanceof ScrollView ? (ScrollView) scrollerValue : null;
        } catch (ReflectiveOperationException ignored) {
            column = null;
            scroller = null;
            resetBottomSwipe();
        }
    }

    private void apply() {
        if (!isEnabled() || column == null || mainActivity.adapter == null) return;
        int count = Math.min(column.getChildCount(), mainActivity.adapter.getCount());
        for (int position = 0; position < count; position++) {
            View wrapper = column.getChildAt(position);
            Result<?> result = mainActivity.adapter.getItem(position);
            final int adapterPosition = position;

            applyLaunchStats(wrapper, result);
            applyEasyIconTap(wrapper, result, adapterPosition);
            applyBottomSwipeTouchRecursively(wrapper);

            View.OnLongClickListener historyFirstLongPress = v -> {
                if (mainActivity.adapter.showNotificationHistoryIfAvailable(adapterPosition, v)) {
                    return true;
                }
                mainActivity.adapter.onLongClick(adapterPosition, v);
                return true;
            };
            applyLongPressRecursively(wrapper, historyFirstLongPress);

            if (result.getPojo() instanceof NotificationPojo) {
                View.OnClickListener notificationClick = v ->
                        mainActivity.adapter.onClick(adapterPosition, v);
                applyNotificationClickRecursively(wrapper, notificationClick);
            }
        }
    }

    private void applyBottomSwipeTouchRecursively(View view) {
        if (view == null || view instanceof Button) return;
        view.setOnTouchListener(this::handleBottomCardSwipeTouch);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyBottomSwipeTouchRecursively(group.getChildAt(i));
        }
    }

    private boolean handleBottomCardSwipeTouch(View source, MotionEvent event) {
        if (!isEnabled() || scroller == null || event == null) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resetBottomSwipe();
                if (scroller.canScrollVertically(1)) return false;
                bottomSwipeStartedOnCard = true;
                bottomSwipeDownRawX = event.getRawX();
                bottomSwipeDownRawY = event.getRawY();
                return false;

            case MotionEvent.ACTION_MOVE:
                if (!bottomSwipeStartedOnCard || bottomSwipeTriggered) return bottomSwipeTriggered;
                float deltaX = event.getRawX() - bottomSwipeDownRawX;
                float deltaY = event.getRawY() - bottomSwipeDownRawY;
                float absX = Math.abs(deltaX);
                float absY = Math.abs(deltaY);
                float threshold = BOTTOM_SWIPE_THRESHOLD_DP
                        * mainActivity.getResources().getDisplayMetrics().density;

                if (deltaY >= 0f || absY < threshold || absY <= absX * BOTTOM_SWIPE_AXIS_BIAS) {
                    return false;
                }

                bottomSwipeTriggered = true;
                dispatchCleanConfiguredSwipeUp(source, event.getRawX(), event.getRawY());
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean consumed = bottomSwipeTriggered;
                resetBottomSwipe();
                return consumed;

            default:
                return bottomSwipeTriggered;
        }
    }

    private void dispatchCleanConfiguredSwipeUp(View source, float rawX, float rawY) {
        long downTime = android.os.SystemClock.uptimeMillis();
        float distance = Math.max(
                140f * mainActivity.getResources().getDisplayMetrics().density,
                240f);
        MotionEvent down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, rawX, rawY + distance, 0);
        MotionEvent up = MotionEvent.obtain(
                downTime, downTime + 70L, MotionEvent.ACTION_UP, rawX, rawY, 0);
        try {
            mainActivity.onTouch(source, down);
            mainActivity.onTouch(source, up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private void resetBottomSwipe() {
        bottomSwipeDownRawX = 0f;
        bottomSwipeDownRawY = 0f;
        bottomSwipeStartedOnCard = false;
        bottomSwipeTriggered = false;
    }

    private void applyEasyIconTap(View wrapper, Result<?> result, int adapterPosition) {
        if (wrapper == null || result == null || result.getPojo() == null) return;
        Pojo pojo = result.getPojo();
        if (!(pojo instanceof AppPojo)
                && !(pojo instanceof ShortcutPojo)
                && !(pojo instanceof DisabledAppPojo)) {
            return;
        }

        ImageView icon = findFirstVisibleImage(wrapper);
        if (icon == null) return;

        icon.setClickable(true);
        icon.setOnClickListener(v -> mainActivity.adapter.onClick(adapterPosition, wrapper));

        if (!(icon.getParent() instanceof ViewGroup)) return;
        ViewGroup touchParent = (ViewGroup) icon.getParent();
        touchParent.post(() -> {
            if (icon.getParent() != touchParent || !icon.isShown()) return;
            Rect hit = new Rect();
            icon.getHitRect(hit);
            int extra = Math.round(18f * mainActivity.getResources().getDisplayMetrics().density);
            hit.left -= extra;
            hit.top -= extra;
            hit.right += extra;
            hit.bottom += extra;
            touchParent.setTouchDelegate(new TouchDelegate(hit, icon));
        });
    }

    private ImageView findFirstVisibleImage(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof ImageView) return (ImageView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ImageView found = findFirstVisibleImage(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private void applyLaunchStats(View wrapper, Result<?> result) {
        if (!(wrapper instanceof ViewGroup) || result == null || result.getPojo() == null) return;
        ViewGroup group = (ViewGroup) wrapper;
        AutoMarqueeTextView strip = null;

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
        if (!TextUtils.equals(strip.getText(), summary)) strip.setText(summary);
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
