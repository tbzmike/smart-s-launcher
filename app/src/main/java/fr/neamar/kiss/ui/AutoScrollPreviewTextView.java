package fr.neamar.kiss.ui;

import android.content.Context;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.View;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * Two-line notification preview that advances through longer text a line at a time.
 * The view keeps the complete text layout, caps its visible height to two lines, and
 * scrolls vertically only while more lines exist.
 */
public class AutoScrollPreviewTextView extends AppCompatTextView {
    private static final int VISIBLE_LINES = 2;
    private static final long STEP_DELAY_MS = 2400L;
    private static final long RESET_DELAY_MS = 3200L;

    private int firstVisibleLine;
    private boolean attached;

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
        setSingleLine(false);
        setHorizontallyScrolling(false);
        setEllipsize(null);
        setHorizontalFadingEdgeEnabled(false);
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(dp(8));
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        SmartTextAppearance.applySearchBody(this);
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
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int cappedHeight = getCompoundPaddingTop() + getCompoundPaddingBottom()
                + getLineHeight() * VISIBLE_LINES;
        int desiredHeight = Math.min(getMeasuredHeight(), cappedHeight);
        int mode = View.MeasureSpec.getMode(heightMeasureSpec);
        int size = View.MeasureSpec.getSize(heightMeasureSpec);
        if (mode == View.MeasureSpec.EXACTLY) desiredHeight = size;
        else if (mode == View.MeasureSpec.AT_MOST) desiredHeight = Math.min(desiredHeight, size);

        setMeasuredDimension(getMeasuredWidth(), desiredHeight);
    }

    private void restartAutoScroll() {
        removeCallbacks(scrollStep);
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
