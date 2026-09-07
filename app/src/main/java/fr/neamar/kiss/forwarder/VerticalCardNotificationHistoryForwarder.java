package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * Decoration is applied only when cards are created/rebuilt. It must never run from a permanent
 * global-layout listener because that would recursively walk every card during ordinary layouts
 * and scrolling, and it could overwrite independent metadata such as today's usage time.
 */
final class VerticalCardNotificationHistoryForwarder extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final String STATS_MARKER = "  •  Last: ";
    private static final String TIMELINE_PREVIEW_TAG = "smart-notification-timeline-preview";
    private static final String DETAILS_TOGGLE_DESCRIPTION = "Show card details";
    private static final float BOTTOM_SWIPE_THRESHOLD_DP = 28f;
    private static final float BOTTOM_SWIPE_AXIS_BIAS = 1.15f;
    private static final long ATTENTION_PULSE_MS = 550L;

    private final SmartCardListForwarder smartCardListForwarder;
    private final Handler attentionHandler = new Handler(Looper.getMainLooper());
    private final List<AttentionBorder> attentionBorders = new ArrayList<>();
    private ViewGroup column;
    private ScrollView scroller;
    private Map<String, LaunchHistoryStatsStore.Stats> launchStats = Collections.emptyMap();
    private boolean attentionBright;

    private float bottomSwipeDownRawX;
    private float bottomSwipeDownRawY;
    private boolean bottomSwipeStartedOnCard;
    private boolean bottomSwipeTriggered;

    private final Runnable attentionPulse = new Runnable() {
        @Override
        public void run() {
            attentionBright = !attentionBright;
            for (int i = attentionBorders.size() - 1; i >= 0; i--) {
                AttentionBorder binding = attentionBorders.get(i);
                if (!binding.card.isAttachedToWindow()
                        || !NotificationTimelineState.isUnread(mainActivity, binding.notificationId)) {
                    binding.card.getOverlay().remove(binding.border);
                    attentionBorders.remove(i);
                    continue;
                }
                binding.border.setBounds(0, 0,
                        Math.max(1, binding.card.getWidth()), Math.max(1, binding.card.getHeight()));
                int stroke = attentionBright ? dp(4) : dp(2);
                int color = attentionBright
                        ? Color.argb(255, 255, 255, 255)
                        : Color.argb(235, 255, 176, 32);
                binding.border.setStroke(stroke, color);
                binding.card.invalidate();
            }
            if (!attentionBorders.isEmpty()) {
                attentionHandler.postDelayed(this, ATTENTION_PULSE_MS);
            }
        }
    };

    VerticalCardNotificationHistoryForwarder(MainActivity activity,
                                             SmartCardListForwarder smartCardListForwarder) {
        super(activity);
        this.smartCardListForwarder = smartCardListForwarder;
    }

    void onCreate() { refresh(); }
    void onResume() { refresh(); }
    void onDataSetChanged() { refresh(); }
    void onConfigurationChanged() { refresh(); }

    void onPause() {
        resetBottomSwipe();
        resetAttentionBorders();
    }

    void onDestroy() {
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
        if (!isEnabled()) {
            launchStats = Collections.emptyMap();
            column = null;
            scroller = null;
            resetBottomSwipe();
            resetAttentionBorders();
            return;
        }
        launchStats = LaunchHistoryStatsStore.getAll(mainActivity);
        resolveViews();
        if (column != null) column.post(this::apply);
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
        if (!isEnabled() || column == null || mainActivity.adapter == null) return;
        resetAttentionBorders();
        int count = Math.min(column.getChildCount(), mainActivity.adapter.getCount());
        for (int position = 0; position < count; position++) {
            View wrapper = column.getChildAt(position);
            Result<?> result = mainActivity.adapter.getItem(position);
            final int adapterPosition = position;

            applyLaunchStats(wrapper, result);
            applyEasyIconTap(wrapper, result, adapterPosition);

            NotificationPojo notification = result.getPojo() instanceof NotificationPojo
                    ? (NotificationPojo) result.getPojo() : null;
            if (notification != null
                    && notification.id.startsWith(NotificationListener.NOTIFICATION_SCHEME)) {
                applyNotificationTimelinePreview(wrapper, notification);
                attachAttentionBorder(wrapper, notification);
            }

            applyBottomSwipeTouchRecursively(wrapper);

            View.OnLongClickListener historyFirstLongPress = v -> {
                if (UiEditLock.isLocked(mainActivity)
                        && mainActivity.adapter.showNotificationHistoryIfAvailable(adapterPosition, v)) {
                    return true;
                }
                mainActivity.adapter.onLongClick(adapterPosition, v);
                return true;
            };
            applyLongPressRecursively(wrapper, historyFirstLongPress);

            if (notification != null) {
                View.OnClickListener notificationClick = v -> {
                    if (notification.id.startsWith(NotificationListener.NOTIFICATION_SCHEME)) {
                        NotificationTimelineState.markRead(mainActivity, notification.id);
                        clearAttentionFor(notification.id);
                    }
                    mainActivity.adapter.onClick(adapterPosition, v);
                };
                applyNotificationClickRecursively(wrapper, notificationClick);
            }
        }
        startAttentionPulse();
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
        if (!NotificationTimelineState.isUnread(mainActivity, notification.id)) return;
        View card = cardView(wrapper);
        if (card == null) return;

        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(dp(22));
        border.setStroke(dp(2), Color.argb(235, 255, 176, 32));
        AttentionBorder binding = new AttentionBorder(card, border, notification.id);
        attentionBorders.add(binding);
        card.post(() -> {
            if (!attentionBorders.contains(binding)
                    || !card.isAttachedToWindow()
                    || !NotificationTimelineState.isUnread(mainActivity, notification.id)) return;
            border.setBounds(0, 0, Math.max(1, card.getWidth()), Math.max(1, card.getHeight()));
            card.getOverlay().add(border);
            card.invalidate();
        });
    }

    private View cardView(View wrapper) {
        if (!(wrapper instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) wrapper;
        return group.getChildCount() > 0 ? group.getChildAt(0) : null;
    }

    private void startAttentionPulse() {
        attentionHandler.removeCallbacks(attentionPulse);
        if (attentionBorders.isEmpty()) return;
        attentionBright = false;
        attentionHandler.post(attentionPulse);
    }

    private void clearAttentionFor(String notificationId) {
        for (int i = attentionBorders.size() - 1; i >= 0; i--) {
            AttentionBorder binding = attentionBorders.get(i);
            if (!TextUtils.equals(notificationId, binding.notificationId)) continue;
            binding.card.getOverlay().remove(binding.border);
            binding.card.invalidate();
            attentionBorders.remove(i);
        }
        if (attentionBorders.isEmpty()) attentionHandler.removeCallbacks(attentionPulse);
    }

    private void resetAttentionBorders() {
        attentionHandler.removeCallbacks(attentionPulse);
        for (AttentionBorder binding : attentionBorders) {
            binding.card.getOverlay().remove(binding.border);
            binding.card.invalidate();
        }
        attentionBorders.clear();
        attentionBright = false;
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
        final GradientDrawable border;
        final String notificationId;

        AttentionBorder(View card, GradientDrawable border, String notificationId) {
            this.card = card;
            this.border = border;
            this.notificationId = notificationId;
        }
    }
}
