package fr.neamar.kiss.ui;

import android.content.Context;
import android.text.Layout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * Notification/message preview shared by Smart S result tiles.
 * Auto-scroll keeps the compact two-line stepping preview. Auto-expand removes the timer and height
 * cap so every laid-out line contributes to the tile height and the complete text stays visible.
 */
public class AutoScrollPreviewTextView extends AppCompatTextView {
    private static final int VISIBLE_LINES = 2;
    private static final long STEP_DELAY_MS = 2400L;
    private static final long RESET_DELAY_MS = 3200L;

    private int firstVisibleLine;
    private boolean attached;
    private boolean behaviorLocked;

    private final Runnable scrollStep = this::advancePreview;

    public AutoScrollPreviewTextView(Context context) {
        super(context);
        init();
    }

    public AutoScrollPreviewTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AutoScrollPreviewTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        behaviorLocked = false;
        super.setSingleLine(false);
        super.setMaxLines(Integer.MAX_VALUE);
        super.setHorizontallyScrolling(false);
        super.setEllipsize(null);
        setFocusable(false);
        setFocusableInTouchMode(false);
        behaviorLocked = true;
        applyConfiguredBehavior();
    }

    private boolean isAutoExpand() {
        return TextOverflowMode.isAutoExpandForHistory(getContext());
    }

    @Override
    public void setSingleLine(boolean singleLine) {
        if (behaviorLocked) {
            super.setSingleLine(false);
            super.setMaxLines(Integer.MAX_VALUE);
            return;
        }
        super.setSingleLine(singleLine);
    }

    @Override
    public void setMaxLines(int maxLines) {
        super.setMaxLines(behaviorLocked ? Integer.MAX_VALUE : maxLines);
    }

    @Override
    public void setEllipsize(TextUtils.TruncateAt where) {
        super.setEllipsize(behaviorLocked ? null : where);
    }

    @Override
    public void setHorizontallyScrolling(boolean whether) {
        super.setHorizontallyScrolling(behaviorLocked ? false : whether);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        SmartTextAppearance.applySearchBody(this);
        applyConfiguredBehavior();
        if (isAutoExpand()) {
            removeCallbacks(scrollStep);
            firstVisibleLine = 0;
            scrollTo(0, 0);
            requestLayout();
        } else {
            post(this::restartAutoScroll);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(scrollStep);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        removeCallbacks(scrollStep);
        firstVisibleLine = 0;
        scrollTo(0, 0);
        if (!attached) return;
        if (isAutoExpand()) requestLayout();
        else post(this::restartAutoScroll);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (attached && (width != oldWidth || height != oldHeight) && !isAutoExpand()) {
            post(this::restartAutoScroll);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        applyConfiguredBehavior();
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (isAutoExpand()) return;

        Layout layout = getLayout();
        int contentHeight;
        if (layout != null && layout.getLineCount() > 0) {
            int visibleLineCount = Math.min(VISIBLE_LINES, layout.getLineCount());
            contentHeight = layout.getLineBottom(visibleLineCount - 1);
        } else {
            contentHeight = getLineHeight() * VISIBLE_LINES;
        }
        int cappedHeight = getCompoundPaddingTop() + getCompoundPaddingBottom() + contentHeight;
        int desiredHeight = Math.min(getMeasuredHeight(), cappedHeight);
        int mode = View.MeasureSpec.getMode(heightMeasureSpec);
        int size = View.MeasureSpec.getSize(heightMeasureSpec);
        if (mode == View.MeasureSpec.EXACTLY) desiredHeight = size;
        else if (mode == View.MeasureSpec.AT_MOST) desiredHeight = Math.min(desiredHeight, size);

        setMeasuredDimension(getMeasuredWidth(), desiredHeight);
    }

    private void applyConfiguredBehavior() {
        if (!behaviorLocked) return;
        super.setSingleLine(false);
        super.setMaxLines(Integer.MAX_VALUE);
        super.setHorizontallyScrolling(false);
        super.setEllipsize(null);
        setHorizontalFadingEdgeEnabled(false);
        setVerticalFadingEdgeEnabled(!isAutoExpand());
        if (!isAutoExpand()) setFadingEdgeLength(dp(8));
    }

    private void restartAutoScroll() {
        removeCallbacks(scrollStep);
        applyConfiguredBehavior();
        firstVisibleLine = 0;
        scrollTo(0, 0);
        if (attached && !isAutoExpand()) postDelayed(scrollStep, STEP_DELAY_MS);
    }

    private void advancePreview() {
        if (!attached || isAutoExpand()) return;
        Layout layout = getLayout();
        if (layout == null) {
            postDelayed(scrollStep, STEP_DELAY_MS);
            return;
        }

        int lineCount = layout.getLineCount();
        if (lineCount <= VISIBLE_LINES) {
            firstVisibleLine = 0;
            scrollTo(0, 0);
            return;
        }

        int maxFirstLine = lineCount - VISIBLE_LINES;
        if (firstVisibleLine >= maxFirstLine) {
            firstVisibleLine = 0;
            scrollTo(0, 0);
            postDelayed(scrollStep, RESET_DELAY_MS);
            return;
        }

        firstVisibleLine++;
        int visibleTextHeight = Math.max(0,
                getHeight() - getCompoundPaddingTop() - getCompoundPaddingBottom());
        int maxScroll = Math.max(0, layout.getHeight() - visibleTextHeight);
        int target = Math.min(layout.getLineTop(firstVisibleLine), maxScroll);
        scrollTo(0, target);
        postDelayed(scrollStep, STEP_DELAY_MS);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
