package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Layout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * Notification/message preview shared by Smart S result tiles.
 * Auto-scroll keeps the compact two-line stepping preview. Auto-expand removes the timer but starts
 * at roughly half of the wrapped message and exposes a dedicated arrow for full-text expansion.
 */
public class AutoScrollPreviewTextView extends AppCompatTextView {
    private static final int VISIBLE_LINES = 2;
    private static final long STEP_DELAY_MS = 2400L;
    private static final long RESET_DELAY_MS = 3200L;

    private int firstVisibleLine;
    private boolean attached;
    private boolean behaviorLocked;
    private boolean expanded;
    private boolean expandable;
    private boolean arrowGesture;
    private boolean scrollStepScheduled;
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect visibleRect = new Rect();

    private final Runnable scrollStep = () -> {
        scrollStepScheduled = false;
        advancePreview();
    };
    private final Runnable deferredLayoutRequest = () -> {
        if (attached) requestLayout();
    };

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
        expanded = false;
        expandable = false;
        arrowGesture = false;
        scrollStepScheduled = false;
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
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
            cancelScrollStep();
            firstVisibleLine = 0;
            scrollTo(0, 0);
            scheduleLayoutRequest();
        } else {
            post(this::restartAutoScroll);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        arrowGesture = false;
        cancelScrollStep();
        removeCallbacks(deferredLayoutRequest);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        cancelScrollStep();
        firstVisibleLine = 0;
        expanded = false;
        expandable = false;
        arrowGesture = false;
        scrollTo(0, 0);
        if (!attached) return;
        if (isAutoExpand()) scheduleLayoutRequest();
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

        Layout layout = getLayout();
        if (isAutoExpand()) {
            int totalLines = layout == null ? 0 : layout.getLineCount();
            int collapsedLines = TextOverflowMode.collapsedPreviewLineCount(totalLines);
            expandable = totalLines > collapsedLines && collapsedLines > 0;
            if (!expandable) expanded = false;

            if (expandable && !expanded && layout != null) {
                int contentHeight = layout.getLineBottom(collapsedLines - 1);
                int desiredHeight = getCompoundPaddingTop() + getCompoundPaddingBottom() + contentHeight;
                int mode = View.MeasureSpec.getMode(heightMeasureSpec);
                int size = View.MeasureSpec.getSize(heightMeasureSpec);
                if (mode == View.MeasureSpec.EXACTLY) desiredHeight = size;
                else if (mode == View.MeasureSpec.AT_MOST) desiredHeight = Math.min(desiredHeight, size);
                setMeasuredDimension(getMeasuredWidth(), desiredHeight);
            }
            return;
        }

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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isAutoExpand()) {
            // ScrollView keeps every card attached. Starting the timer from draw means only rows
            // that actually intersect the viewport can own an auto-scroll callback; off-screen
            // notification previews perform no periodic work while the user scrolls elsewhere.
            scheduleScrollStep(STEP_DELAY_MS);
            return;
        }
        if (!expandable) return;

        float centerX = getWidth() - getPaddingRight() - dp(12);
        float centerY = getHeight() / 2f;
        float half = dp(5);
        arrowPaint.setColor(getCurrentTextColor());
        arrowPaint.setAlpha(220);
        arrowPaint.setStrokeWidth(Math.max(1f, dp(2)));

        if (expanded) {
            canvas.drawLine(centerX - half, centerY + half, centerX, centerY - half, arrowPaint);
            canvas.drawLine(centerX, centerY - half, centerX + half, centerY + half, arrowPaint);
        } else {
            canvas.drawLine(centerX - half, centerY - half, centerX, centerY + half, arrowPaint);
            canvas.drawLine(centerX, centerY + half, centerX + half, centerY - half, arrowPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isAutoExpand() && expandable) {
            boolean inArrowArea = event.getX() >= getWidth() - getPaddingRight() - dp(40);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (inArrowArea) {
                        arrowGesture = true;
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (arrowGesture) return true;
                    break;
                case MotionEvent.ACTION_UP:
                    if (arrowGesture) {
                        boolean activate = inArrowArea;
                        arrowGesture = false;
                        if (activate) toggleExpandedMessage();
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (arrowGesture) {
                        arrowGesture = false;
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return super.onTouchEvent(event);
    }

    private void toggleExpandedMessage() {
        if (!expandable || !isAutoExpand()) return;
        expanded = !expanded;
        scrollTo(0, 0);
        requestLayout();
        invalidate();
        announceForAccessibility(expanded ? "Message expanded" : "Message collapsed");
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

    private void scheduleLayoutRequest() {
        removeCallbacks(deferredLayoutRequest);
        postOnAnimation(deferredLayoutRequest);
    }

    private boolean isActuallyVisibleOnScreen() {
        if (!attached || !isShown() || !hasWindowFocus()) return false;
        visibleRect.setEmpty();
        return getGlobalVisibleRect(visibleRect)
                && visibleRect.width() > 0
                && visibleRect.height() > 0;
    }

    private void cancelScrollStep() {
        removeCallbacks(scrollStep);
        scrollStepScheduled = false;
    }

    private void scheduleScrollStep(long delayMs) {
        if (scrollStepScheduled || isAutoExpand() || !isActuallyVisibleOnScreen()) return;
        scrollStepScheduled = true;
        postDelayed(scrollStep, delayMs);
    }

    private void restartAutoScroll() {
        cancelScrollStep();
        applyConfiguredBehavior();
        firstVisibleLine = 0;
        scrollTo(0, 0);
        scheduleScrollStep(STEP_DELAY_MS);
    }

    private void advancePreview() {
        if (!attached || isAutoExpand() || !isActuallyVisibleOnScreen()) return;
        Layout layout = getLayout();
        if (layout == null) {
            scheduleScrollStep(STEP_DELAY_MS);
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
            scheduleScrollStep(RESET_DELAY_MS);
            return;
        }

        firstVisibleLine++;
        int visibleTextHeight = Math.max(0,
                getHeight() - getCompoundPaddingTop() - getCompoundPaddingBottom());
        int maxScroll = Math.max(0, layout.getHeight() - visibleTextHeight);
        int target = Math.min(layout.getLineTop(firstVisibleLine), maxScroll);
        scrollTo(0, target);
        scheduleScrollStep(STEP_DELAY_MS);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
