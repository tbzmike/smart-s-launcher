package fr.neamar.kiss.forwarder;

import android.graphics.Rect;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
 */
final class VerticalCardNotificationHistoryForwarder extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final String STATS_MARKER = "  •  Last: ";
    private static final float BOTTOM_SWIPE_THRESHOLD_DP = 28f;
    private static final float BOTTOM_SWIPE_AXIS_BIAS = 1.15f;

    private final SmartCardListForwarder smartCardListForwarder;
    private ViewGroup column;
    private ScrollView scroller;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private Map<String, LaunchHistoryStatsStore.Stats> launchStats = Collections.emptyMap();

    private MotionEvent pendingBottomSwipeDown;
    private boolean bottomSwipeStartedOnCard;
    private boolean forwardingBottomSwipe;

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
        scroller = null;
        launchStats = Collections.emptyMap();
        resetBottomSwipe();
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
        resolveViews();
        attach();
        if (column != null) column.post(this::apply);
    }

    private void resolveViews() {
        try {
            Field columnField = SmartCardListForwarder.class.getDeclaredField("column");
            columnField.setAccessible(true);
            Object columnValue = columnField.get(smartCardListForwarder);
            ViewGroup newColumn = columnValue instanceof ViewGroup ? (ViewGroup) columnValue : null;

            Field scrollerField = SmartCardListForwarder.class.getDeclaredField("scroller");
            scrollerField.setAccessible(true);
            Object scrollerValue = scrollerField.get(smartCardListForwarder);
            ScrollView newScroller = scrollerValue instanceof ScrollView ? (ScrollView) scrollerValue : null;

            if (newColumn != column || newScroller != scroller) {
                detach();
                column = newColumn;
                scroller = newScroller;
            }
        } catch (ReflectiveOperationException ignored) {
            detach();
            column = null;
            scroller = null;
        }
    }

    private void attach() {
        if (column == null) return;
        if (layoutListener == null) {
            layoutListener = this::apply;
            column.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        }
        if (scroller != null) {
            // Once the ScrollView intercepts an upward drag from a child card, continue observing
            // that same gesture here. This listener does not arm gestures that begin on empty space;
            // those already belong to the launcher's normal root gesture path.
            scroller.setOnTouchListener(this::handleBottomCardSwipeTouch);
        }
    }

    private void detach() {
        if (column != null && layoutListener != null) {
            ViewTreeObserver observer = column.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
        }
        layoutListener = null;
        if (scroller != null) scroller.setOnTouchListener(null);
        resetBottomSwipe();
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

    /**
     * Observe card touches without replacing their click/long-click behavior. A gesture is eligible
     * only when ACTION_DOWN happened on a card while the Vertical Cards scroller was already at its
     * bottom/newest position. Until a deliberate upward drag is verified this listener returns false.
     */
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
                // A DOWN delivered directly to the ScrollView means empty/padding space. The root
                // launcher gesture system already handles that case, so do not arm a second path.
                if (source == scroller) return false;
                resetBottomSwipe();
                if (scroller.canScrollVertically(1)) return false;
                bottomSwipeStartedOnCard = true;
                pendingBottomSwipeDown = MotionEvent.obtain(event);
                return false;

            case MotionEvent.ACTION_MOVE:
                if (!bottomSwipeStartedOnCard || pendingBottomSwipeDown == null) return false;
                float deltaX = event.getRawX() - pendingBottomSwipeDown.getRawX();
                float deltaY = event.getRawY() - pendingBottomSwipeDown.getRawY();
                float absX = Math.abs(deltaX);
                float absY = Math.abs(deltaY);
                float threshold = BOTTOM_SWIPE_THRESHOLD_DP
                        * mainActivity.getResources().getDisplayMetrics().density;

                if (!forwardingBottomSwipe) {
                    if (deltaY >= 0f || absY < threshold || absY <= absX * BOTTOM_SWIPE_AXIS_BIAS) {
                        return false;
                    }

                    // Reuse the exact launcher gesture detector that handles empty-space swipes.
                    // Feed it the stored original DOWN first, then the current verified MOVE.
                    forwardingBottomSwipe = true;
                    mainActivity.onTouch(source, pendingBottomSwipeDown);
                    mainActivity.onTouch(source, event);
                } else {
                    mainActivity.onTouch(source, event);
                }

                // Once the ScrollView owns the drag, consume it so overscroll/card clicks cannot
                // compete with the configured launcher gesture.
                return source == scroller;

            case MotionEvent.ACTION_UP:
                if (forwardingBottomSwipe) {
                    mainActivity.onTouch(source, event);
                    resetBottomSwipe();
                    return true;
                }
                resetBottomSwipe();
                return false;

            case MotionEvent.ACTION_CANCEL:
                // A child card receives CANCEL when ScrollView starts intercepting its drag. Keep
                // the stored DOWN alive so the ScrollView listener can finish the same gesture.
                if (source == scroller) resetBottomSwipe();
                return false;

            default:
                return forwardingBottomSwipe && source == scroller;
        }
    }

    private void resetBottomSwipe() {
        if (pendingBottomSwipeDown != null) {
            pendingBottomSwipeDown.recycle();
            pendingBottomSwipeDown = null;
        }
        bottomSwipeStartedOnCard = false;
        forwardingBottomSwipe = false;
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
        // Forward the whole card wrapper as the launch source so the new transition flips and
        // expands the actual tile, while the enlarged invisible icon hit target remains intact.
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
