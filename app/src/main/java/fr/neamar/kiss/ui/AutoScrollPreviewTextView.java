package fr.neamar.kiss.ui;

import android.content.Context;
import android.text.Layout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * Two-line preview that advances through longer text a complete line at a time.
 * The complete text remains laid out: no caller may convert this view back to a one-line
 * marquee or END-ellipsis it. This protects message bodies from generic history styling.
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
        setHorizontalFadingEdgeEnabled(false);
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(dp(8));
        setFocusable(false);
        setFocusableInTouchMode(false);
        behaviorLocked = true;
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
        restoreFullTextBehavior();
        post(this::restartAutoScroll);
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
        firstVisibleLine = 0;
        scrollTo(0, 0);
        if (attached) post(this::restartAutoScroll);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (attached && (width != oldWidth || height != oldHeight)) post(this::restartAutoScroll);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        restoreFullTextBehavior();
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

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

    private void restoreFullTextBehavior() {
        if (!behaviorLocked) return;
        super.setSingleLine(false);
        super.setMaxLines(Integer.MAX_VALUE);
        super.setHorizontallyScrolling(false);
        super.setEllipsize(null);
        setHorizontalFadingEdgeEnabled(false);
    }

    private void restartAutoScroll() {
        removeCallbacks(scrollStep);
        restoreFullTextBehavior();
        firstVisibleLine = 0;
        scrollTo(0, 0);
        if (attached) postDelayed(scrollStep, STEP_DELAY_MS);
    }

    private void advancePreview() {
        if (!attached) return;
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
