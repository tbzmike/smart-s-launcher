package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.LaunchHistoryStatsStore;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.notification.NotificationTimelineState;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.preference.UiEditLock;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.AutoMarqueeTextView;

/**
 * Makes notification-history behavior explicit on the custom Vertical Cards renderer and enriches
 * the existing between-card label with launch activity from the KISS history table.
 *
 * Decoration is applied only when cards are created/rebuilt. Unread notifications keep the
 * original orange/white flashing attention border, but each border now uses a tiny AnimationDrawable
 * overlay instead of a renderer-wide Handler loop. Card geometry is updated only when layout size
 * actually changes, keeping notification animation out of the scrolling hot path.
 */
final class VerticalCardNotificationHistoryForwarder extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final String STATS_MARKER = "  •  Last: ";
    private static final String TIMELINE_PREVIEW_TAG = "smart-notification-timeline-preview";
    private static final String DETAILS_TOGGLE_DESCRIPTION = "Show card details";
    private static final float BOTTOM_SWIPE_THRESHOLD_DP = 28f;
    private static final float BOTTOM_SWIPE_AXIS_BIAS = 1.15f;
    private static final int ATTENTION_PULSE_MS = 550;

    private final SmartCardListForwarder smartCardListForwarder;
    private final ExecutorService launchStatsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "smart-s-card-launch-stats");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final AtomicBoolean launchStatsRefreshInFlight = new AtomicBoolean(false);
    private final List<AttentionBorder> attentionBorders = new ArrayList<>();
    private ViewGroup column;
    private ScrollView scroller;
    private Map<String, LaunchHistoryStatsStore.Stats> launchStats = Collections.emptyMap();
    private boolean launchStatsRefreshRequested;
    private boolean paused;
    private volatile boolean destroyed;

    private float bottomSwipeDownRawX;
    private float bottomSwipeDownRawY;
    private boolean bottomSwipeStartedOnCard;
    private boolean bottomSwipeTriggered;

    VerticalCardNotificationHistoryForwarder(MainActivity activity,
                                             SmartCardListForwarder smartCardListForwarder) {
        super(activity);
        this.smartCardListForwarder = smartCardListForwarder;
    }

    void onCreate() { paused = false; refresh(); }
    void onResume() { paused = false; refresh(); }
    void onDataSetChanged() { refresh(); }
    void onConfigurationChanged() { refresh(); }

    void onPause() {
        paused = true;
        resetBottomSwipe();
        resetAttentionBorders();
    }

    void onDestroy() {
        destroyed = true;
        paused = true;
        launchStatsExecutor.shutdownNow();
        resetBottomSwipe();
        resetAttentionBorders();
        column = null;
        scroller = null;
        launchStats = Collections.emptyMap();
    }

    private boolean isEnabled() {
        return VERTICAL_CARDS.equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private void refresh() {
        if (smartCardListForwarder.isScrollInProgress()) {
            smartCardListForwarder.runWhenScrollIdle(this::refresh);
            return;
        }
        if (!isEnabled()) {
            launchStats = Collections.emptyMap();
            column = null;
            scroller = null;
            resetBottomSwipe();
            resetAttentionBorders();
            return;
        }
        resolveViews();
        if (!paused && column != null) column.post(this::apply);
        refreshLaunchStatsAsync();
    }

    private void refreshLaunchStatsAsync() {
        if (destroyed || paused || !isEnabled()) return;
        if (!launchStatsRefreshInFlight.compareAndSet(false, true)) {
            launchStatsRefreshRequested = true;
            return;
        }
        final android.content.Context appContext = mainActivity.getApplicationContext();
        launchStatsExecutor.execute(() -> {
            Map<String, LaunchHistoryStatsStore.Stats> fresh;
            try {
                fresh = LaunchHistoryStatsStore.getAll(appContext);
            } catch (RuntimeException ignored) {
                fresh = Collections.emptyMap();
            }
            final Map<String, LaunchHistoryStatsStore.Stats> result = fresh;
            mainActivity.runOnUiThread(() -> {
                launchStatsRefreshInFlight.set(false);
                if (destroyed) return;
                launchStats = result;
                resolveViews();
                if (!paused && column != null) column.post(this::apply);
                if (launchStatsRefreshRequested) {
                    launchStatsRefreshRequested = false;
                    refreshLaunchStatsAsync();
                }
            });
        });
    }

    private void resolveViews() {
        column = smartCardListForwarder.getColumn();
        scroller = smartCardListForwarder.getScroller();
        if (column == null || scroller == null) {
            resetBottomSwipe();
            resetAttentionBorders();
        }
    }

    private void apply() {
        if (smartCardListForwarder.isScrollInProgress()) {
            smartCardListForwarder.runWhenScrollIdle(this::apply);
            return;
        }
        if (paused || destroyed || !isEnabled() || column == null || mainActivity.adapter == null) return;
        resetAttentionBorders();
        Map<String, Result<?>> resultsByPojoId = new HashMap<>();
        for (int position = 0; position < mainActivity.adapter.getCount(); position++) {
            Result<?> result = mainActivity.adapter.getItem(position);
            if (result == null) continue;
            resultsByPojoId.put(result.getPojoId(), result);
        }

        int count = column.getChildCount();
        for (int position = 0; position < count; position++) {
            View wrapper = column.getChildAt(position);
            Object wrapperId = wrapper.getTag();
            if (!(wrapperId instanceof String)) continue;
            String stableId = (String) wrapperId;
            Result<?> result = resultsByPojoId.get(stableId);
            if (result == null) continue;

            applyLaunchStats(wrapper, result);
            applyEasyIconTap(wrapper, result, stableId);

            NotificationPojo notification = result.getPojo() instanceof NotificationPojo
                    ? (NotificationPojo) result.getPojo() : null;
            if (notification != null
                    && notification.exactNotificationId.startsWith(
                    NotificationListener.NOTIFICATION_SCHEME)) {
                applyNotificationTimelinePreview(wrapper, notification);
                attachAttentionBorder(wrapper, notification);
            }

            applyBottomSwipeTouchRecursively(wrapper);

            View.OnLongClickListener historyFirstLongPress = v -> {
                int currentPosition = resolveAdapterPosition(stableId);
                if (currentPosition < 0) return false;
                if (UiEditLock.isLocked(mainActivity)
                        && mainActivity.adapter.showNotificationHistoryIfAvailable(currentPosition, v)) {
                    return true;
                }
                mainActivity.adapter.onLongClick(currentPosition, v);
                return true;
            };
            applyLongPressRecursively(wrapper, historyFirstLongPress);

            if (notification != null) {
                View.OnClickListener notificationClick = v -> {
                    if (notification.exactNotificationId.startsWith(
                            NotificationListener.NOTIFICATION_SCHEME)) {
                        NotificationTimelineState.markRead(mainActivity,
                                notification.exactNotificationId);
                        clearAttentionFor(notification.exactNotificationId);
                    }
                    int currentPosition = resolveAdapterPosition(stableId);
                    if (currentPosition >= 0) mainActivity.adapter.onClick(currentPosition, v);
                };
                applyNotificationClickRecursively(wrapper, notificationClick);
            }
        }
    }

    /** Replace generic card metadata with the exact individual notification preview. */
    private void applyNotificationTimelinePreview(View wrapper, NotificationPojo notification) {
        View card = cardView(wrapper);
        if (!(card instanceof ViewGroup)) return;
        ViewGroup cardGroup = (ViewGroup) card;
        if (cardGroup.getChildCount() == 0 || !(cardGroup.getChildAt(0) instanceof ViewGroup)) return;
        ViewGroup mainRow = (ViewGroup) cardGroup.getChildAt(0);
        if (mainRow.getChildCount() < 2 || !(mainRow.getChildAt(1) instanceof LinearLayout)) return;
        LinearLayout center = (LinearLayout) mainRow.getChildAt(1);
        if (center.getChildCount() == 0) return;

        View first = center.getChildAt(0);
        if (first instanceof TextView) {
            ((TextView) first).setText(notification.appName);
            first.setContentDescription(notification.appName + " notification");
        }

        // Remove previews we previously injected if refresh() runs without a card rebuild. Preserve
        // the renderer-owned views but hide their generic/group metadata for an individual tile.
        for (int i = center.getChildCount() - 1; i >= 1; i--) {
            View child = center.getChildAt(i);
            if (TIMELINE_PREVIEW_TAG.equals(child.getTag())) center.removeViewAt(i);
            else child.setVisibility(View.GONE);
        }

        String when = timelineTime(notification.postTime);
        String title = notification.latestTitle == null ? "" : notification.latestTitle.trim();
        String body = notification.latestText == null ? "" : notification.latestText.trim();

        AutoMarqueeTextView headline = new AutoMarqueeTextView(mainActivity);
        headline.setTag(TIMELINE_PREVIEW_TAG);
        headline.setText(when + "  •  " + (title.isEmpty() ? "Notification" : title));
        headline.setTextColor(Color.WHITE);
        headline.setTextSize(13.5f);
        headline.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        headline.setPadding(0, dp(2), 0, dp(1));
        center.addView(headline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!body.isEmpty() && !body.equals(title)) {
            AutoMarqueeTextView preview = new AutoMarqueeTextView(mainActivity);
            preview.setTag(TIMELINE_PREVIEW_TAG);
            preview.setText(body);
            preview.setTextColor(Color.argb(238, 255, 255, 255));
            preview.setTextSize(13f);
            preview.setPadding(0, dp(2), 0, dp(2));
            center.addView(preview, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (wrapper instanceof ViewGroup) {
            ViewGroup wrapperGroup = (ViewGroup) wrapper;
            for (int i = wrapperGroup.getChildCount() - 1; i >= 0; i--) {
                View child = wrapperGroup.getChildAt(i);
                if (child instanceof AutoMarqueeTextView) {
                    AutoMarqueeTextView timelineLabel = (AutoMarqueeTextView) child;
                    timelineLabel.setText("Notification  •  " + when);
                    timelineLabel.setContentDescription(
                            "Notification from " + notification.appName + " at " + when);
                    break;
                }
            }
        }
    }

    private String timelineTime(long timestamp) {
        long safe = timestamp > 0L ? timestamp : System.currentTimeMillis();
        String pattern = DateFormat.is24HourFormat(mainActivity) ? "HH:mm:ss" : "h:mm:ss a";
        String time = new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(safe));
        if (DateUtils.isToday(safe)) return time;
        return DateFormat.getMediumDateFormat(mainActivity).format(new Date(safe)) + "  " + time;
    }

    private void attachAttentionBorder(View wrapper, NotificationPojo notification) {
        if (!NotificationTimelineState.isUnread(
                mainActivity, notification.exactNotificationId)) return;
        View card = cardView(wrapper);
        if (card == null) return;

        AnimationDrawable border = new AnimationDrawable();
        border.setOneShot(false);
        border.addFrame(createAttentionFrame(dp(2), Color.argb(235, 255, 176, 32)),
                ATTENTION_PULSE_MS);
        border.addFrame(createAttentionFrame(dp(4), Color.WHITE), ATTENTION_PULSE_MS);

        View.OnLayoutChangeListener layoutListener = (v, left, top, right, bottom,
                                                       oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;
            if (width == oldRight - oldLeft && height == oldBottom - oldTop) return;
            updateAttentionBounds(v, border);
        };
        AttentionBorder binding = new AttentionBorder(
                card, border, notification.exactNotificationId, layoutListener);
        attentionBorders.add(binding);
        card.addOnLayoutChangeListener(layoutListener);
        card.post(() -> {
            if (!attentionBorders.contains(binding)
                    || !card.isAttachedToWindow()
                    || !NotificationTimelineState.isUnread(
                    mainActivity, notification.exactNotificationId)) return;
            updateAttentionBounds(card, border);
            card.getOverlay().add(border);
            border.start();
        });
    }

    private GradientDrawable createAttentionFrame(int strokeWidth, int strokeColor) {
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(Color.TRANSPARENT);
        frame.setCornerRadius(dp(22));
        frame.setStroke(strokeWidth, strokeColor);
        return frame;
    }

    private void updateAttentionBounds(View card, AnimationDrawable border) {
        border.setBounds(0, 0, Math.max(1, card.getWidth()), Math.max(1, card.getHeight()));
    }

    private View cardView(View wrapper) {
        if (!(wrapper instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) wrapper;
        return group.getChildCount() > 0 ? group.getChildAt(0) : null;
    }

    private void clearAttentionFor(String notificationId) {
        for (int i = attentionBorders.size() - 1; i >= 0; i--) {
            AttentionBorder binding = attentionBorders.get(i);
            if (!TextUtils.equals(notificationId, binding.notificationId)) continue;
            removeAttentionBinding(binding);
            attentionBorders.remove(i);
        }
    }

    private void resetAttentionBorders() {
        for (AttentionBorder binding : attentionBorders) removeAttentionBinding(binding);
        attentionBorders.clear();
    }

    private void removeAttentionBinding(AttentionBorder binding) {
        binding.border.stop();
        binding.card.removeOnLayoutChangeListener(binding.layoutListener);
        binding.card.getOverlay().remove(binding.border);
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

    private void applyEasyIconTap(View wrapper, Result<?> result, String stableId) {
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
        icon.setOnClickListener(v -> {
            int currentPosition = resolveAdapterPosition(stableId);
            if (currentPosition >= 0) mainActivity.adapter.onClick(currentPosition, wrapper);
        });

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

    private int resolveAdapterPosition(String stableId) {
        if (mainActivity.adapter == null || TextUtils.isEmpty(stableId)) return -1;
        for (int position = 0; position < mainActivity.adapter.getCount(); position++) {
            Result<?> result = mainActivity.adapter.getItem(position);
            if (result != null && TextUtils.equals(stableId, result.getPojoId())) return position;
        }
        return -1;
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
        if (!(wrapper instanceof ViewGroup) || result == null || result.getPojo() == null
                || result.getPojo() instanceof NotificationPojo) return;
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

    private boolean isDetailsToggle(View view) {
        return view != null
                && TextUtils.equals(DETAILS_TOGGLE_DESCRIPTION, view.getContentDescription());
    }

    private void applyLongPressRecursively(View view, View.OnLongClickListener listener) {
        if (view == null || view instanceof Button || isDetailsToggle(view)) return;
        view.setLongClickable(true);
        view.setOnLongClickListener(listener);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyLongPressRecursively(group.getChildAt(i), listener);
        }
    }

    private void applyNotificationClickRecursively(View view, View.OnClickListener listener) {
        if (view == null || view instanceof Button || isDetailsToggle(view)) return;
        view.setClickable(true);
        view.setOnClickListener(listener);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyNotificationClickRecursively(group.getChildAt(i), listener);
        }
    }

    private int dp(int value) {
        return Math.round(value * mainActivity.getResources().getDisplayMetrics().density);
    }

    private static final class AttentionBorder {
        final View card;
        final AnimationDrawable border;
        final String notificationId;
        final View.OnLayoutChangeListener layoutListener;

        AttentionBorder(View card, AnimationDrawable border, String notificationId,
                        View.OnLayoutChangeListener layoutListener) {
            this.card = card;
            this.border = border;
            this.notificationId = notificationId;
            this.layoutListener = layoutListener;
        }
    }
}
