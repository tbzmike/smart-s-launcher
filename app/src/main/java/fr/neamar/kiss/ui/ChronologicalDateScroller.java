package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Reusable, date-aware fast scroll for chronological lists.
 *
 * Both App Usage (RecyclerView) and Notification History (ListView) own their scrolling listeners.
 * This overlay neither replaces those listeners nor accesses their stores: callers provide a
 * position-to-timestamp index and jump callback from their currently filtered data.
 */
public final class ChronologicalDateScroller {
    public interface TimestampAt {
        long timestampAt(int adapterPosition);
    }

    public interface JumpTo {
        void jumpTo(int adapterPosition, int topOffset);
    }

    private static final int MIN_SCRUB_ROWS = 8;
    private static final long IDLE_FADE_DELAY_MS = 1100L;
    private static final long FADE_DURATION_MS = 240L;
    private final TextView section;
    private final ThumbView thumb;
    private final View content;
    private final int originalTopMargin;
    @Nullable private TimestampAt source;
    @Nullable private JumpTo jump;
    private int itemCount;
    private int visibleCount = 1;
    private int firstVisible;
    private final Runnable fadeRailAfterIdle;

    private void brieflyRevealRail() {
        if (thumb.getVisibility() != View.VISIBLE) return;
        thumb.removeCallbacks(fadeRailAfterIdle);
        if (thumb.getAlpha() < 1f) {
            thumb.animate().cancel();
            thumb.setAlpha(1f);
        }
        if (!thumb.dragging) thumb.postDelayed(fadeRailAfterIdle, IDLE_FADE_DELAY_MS);
    }

    public ChronologicalDateScroller(@NonNull Context context, @NonNull FrameLayout host) {
        content = host.getChildCount() > 0 ? host.getChildAt(0) : null;
        originalTopMargin = content != null
                && content.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? ((FrameLayout.LayoutParams) content.getLayoutParams()).topMargin : 0;
        section = new TextView(context);
        section.setGravity(Gravity.CENTER);
        section.setTextColor(Color.WHITE);
        section.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        section.setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6));
        section.setElevation(dp(context, 5));
        section.setBackground(round(Color.argb(238, 30, 32, 36), dp(context, 16)));
        section.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        section.setVisibility(View.GONE);
        section.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) {
                reserveHeaderSpace(source != null && jump != null && itemCount > 0);
            }
        });
        FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        label.topMargin = dp(context, 6);
        host.addView(section, label);

        thumb = new ThumbView(context);
        fadeRailAfterIdle = () -> {
            if (!thumb.dragging && thumb.getVisibility() == View.VISIBLE) {
                thumb.animate().alpha(0f).setDuration(FADE_DURATION_MS).start();
            }
        };
        FrameLayout.LayoutParams rail = new FrameLayout.LayoutParams(
                dp(context, 160), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        host.addView(thumb, rail);
        thumb.setVisibility(View.GONE);
        thumb.setAlpha(0f);
    }

    /** Set to null for non-chronological screens such as Overview and Detailed Usage. */
    public void setSource(int count, @Nullable TimestampAt timestampAt, @Nullable JumpTo jumpTo) {
        source = timestampAt;
        jump = jumpTo;
        itemCount = Math.max(0, count);
        reserveHeaderSpace(source != null && jump != null && itemCount > 0);
        firstVisible = 0;
        visibleCount = 1;
        if (source == null || jump == null || itemCount == 0) {
            section.setVisibility(View.GONE);
            thumb.removeCallbacks(fadeRailAfterIdle);
            thumb.animate().cancel();
            thumb.setVisibility(View.GONE);
            thumb.setAlpha(0f);
            thumb.dragging = false;
            return;
        }
        section.setVisibility(View.VISIBLE);
        thumb.setVisibility(itemCount >= MIN_SCRUB_ROWS ? View.VISIBLE : View.GONE);
        updateLabel(0);
        thumb.thumbTop = 0;
        thumb.invalidate();
        brieflyRevealRail();
    }

    private void reserveHeaderSpace(boolean active) {
        if (content == null
                || !(content.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) content.getLayoutParams();
        int header = Math.max(dp(section.getContext(), 44),
                section.getHeight() + dp(section.getContext(), 14));
        int wanted = originalTopMargin + (active ? header : 0);
        if (params.topMargin != wanted) {
            params.topMargin = wanted;
            content.setLayoutParams(params);
        }
    }

    public void onScroll(int first, int visible, int total) {
        if (source == null || jump == null || itemCount <= 0) return;
        // Query/filter/range switches may be in the middle of a new adapter layout.
        if (total != itemCount) return;
        firstVisible = Math.max(0, Math.min(first, itemCount - 1));
        visibleCount = Math.max(1, visible);
        if (!thumb.dragging) {
            updateLabel(firstVisible);
            float fraction = DateScrollPositionMath.fraction(firstVisible, visibleCount, itemCount);
            thumb.thumbTop = fraction * Math.max(0, thumb.getHeight() - thumb.thumbHeight);
            thumb.invalidate();
            // A rendered date rail is useful while moving; fading it after idle leaves
            // the full text area unobstructed. Keep it attached for swipe-to-scrub.
            brieflyRevealRail();
        }
    }

    private void updateLabel(int position) {
        if (source == null || itemCount <= 0) return;
        int bounded = Math.max(0, Math.min(position, itemCount - 1));
        final String date = UniversalHistoryTimestamp.formatHistorySectionLabel(
                section.getContext(), source.timestampAt(bounded));
        if (!date.contentEquals(section.getText())) section.setText(date);
    }

    private void scrubTo(float fraction) {
        if (jump == null || source == null || itemCount <= 0) return;
        int target = DateScrollPositionMath.position(fraction, itemCount);
        updateLabel(target);
        thumb.bubbleText = section.getText().toString();
        // Date label must stay visible while the UI jumps to the selected history section.
        jump.jumpTo(target, section.getHeight() + dp(section.getContext(), 8));
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(radius);
        return result;
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private final class ThumbView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint slider = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pill = new RectF();
        private final RectF callout = new RectF();
        private final float thumbHeight;
        private final float thumbWidth;
        private final float bubbleWidth;
        private final float bubbleHeight;
        private boolean dragging;
        private float thumbTop;
        private String bubbleText = "";

        ThumbView(Context context) {
            super(context);
            setWillNotDraw(false);
            setContentDescription("Fast scroll history by date");
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            track.setColor(Color.argb(58, 255, 255, 255));
            slider.setColor(Color.argb(235, 235, 235, 235));
            bubble.setColor(Color.argb(249, 35, 37, 41));
            text.setColor(Color.WHITE);
            text.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 14, context.getResources().getDisplayMetrics()));
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            thumbWidth = dp(context, 5);
            thumbHeight = dp(context, 58);
            bubbleWidth = dp(context, 135);
            bubbleHeight = dp(context, 42);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float x = getWidth() - dp(getContext(), 8) - thumbWidth;
            float centerX = x + thumbWidth / 2;
            canvas.drawRoundRect(centerX - dp(getContext(), 1),
                    dp(getContext(), 8), centerX + dp(getContext(), 1),
                    Math.max(dp(getContext(), 8), getHeight() - dp(getContext(), 8)),
                    dp(getContext(), 1), dp(getContext(), 1), track);

            pill.set(x, thumbTop, x + thumbWidth, Math.min(getHeight(), thumbTop + thumbHeight));
            canvas.drawRoundRect(pill, thumbWidth / 2, thumbWidth / 2, slider);

            if (!dragging || bubbleText.isEmpty()) return;
            float right = x - dp(getContext(), 12);
            float cy = Math.max(bubbleHeight / 2,
                    Math.min(getHeight() - bubbleHeight / 2, pill.centerY()));
            callout.set(right - bubbleWidth, cy - bubbleHeight / 2,
                    right, cy + bubbleHeight / 2);
            canvas.drawRoundRect(callout, dp(getContext(), 16),
                    dp(getContext(), 16), bubble);
            Paint.FontMetrics f = text.getFontMetrics();
            canvas.drawText(bubbleText, callout.centerX(),
                    callout.centerY() - (f.ascent + f.descent) / 2, text);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (itemCount < MIN_SCRUB_ROWS || jump == null || getHeight() <= 0) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (event.getX() < getWidth() - dp(getContext(), 38)
                            || Math.abs(event.getY() - (thumbTop + thumbHeight / 2))
                            > thumbHeight / 2 + dp(getContext(), 20)) return false;
                    dragging = true;
                    brieflyRevealRail();
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragTo(event.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    dragTo(event.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) return false;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) dragTo(event.getY());
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    brieflyRevealRail();
                    invalidate();
                    performClick();
                    return true;
                default:
                    return dragging;
            }
        }

        private void dragTo(float y) {
            float available = Math.max(1f, getHeight() - thumbHeight);
            thumbTop = Math.max(0f, Math.min(available, y - thumbHeight / 2));
            scrubTo(thumbTop / available);
            invalidate();
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
