package fr.neamar.kiss.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Layout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import android.widget.TextView;

import fr.neamar.kiss.utils.LauncherScrollWorkGate;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

/**
 * Notification/message preview shared by Smart S result tiles.
 * Auto-scroll keeps the compact two-line stepping preview. Auto-expand removes the timer but starts
 * at roughly half of the wrapped message and exposes a dedicated arrow for full-text expansion.
 */
@SuppressLint("AppCompatCustomView")
public class AutoScrollPreviewTextView extends TextView {
    private static final int VISIBLE_LINES = 2;
    private static final long STEP_DELAY_MS = 2400L;
    private static final long RESET_DELAY_MS = 3200L;
    private static final long SCROLL_IDLE_RETRY_MS = 320L;

    private int firstVisibleLine;
    private boolean attached;
    private boolean behaviorLocked;
    private boolean autoExpand;
    private int appliedBehaviorMode = -1;
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
    private final Runnable deferredPreviewRestart = () -> {
        if (attached) restartAutoScroll();
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
        autoExpand = TextOverflowMode.isAutoExpandForHistory(getContext());
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
        return autoExpand;
    }

    private void refreshOverflowMode() {
        boolean next = TextOverflowMode.isAutoExpandForHistory(getContext());
        if (next != autoExpand) {
            autoExpand = next;
            appliedBehaviorMode = -1;
        }
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
        refreshOverflowMode();
        applySearchAppearanceIfNeeded();
        applyConfiguredBehavior();
        if (isAutoExpand()) {
            cancelScrollStep();
            firstVisibleLine = 0;
            scrollTo(0, 0);
            scheduleLayoutRequest();
        } else {
            schedulePreviewRestart();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) return;
        refreshOverflowMode();
        applyConfiguredBehavior();
        if (isAutoExpand()) {
            cancelScrollStep();
            scheduleLayoutRequest();
        } else {
            schedulePreviewRestart();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        arrowGesture = false;
        cancelScrollStep();
        removeCallbacks(deferredLayoutRequest);
        removeCallbacks(deferredPreviewRestart);
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
        applySearchAppearanceIfNeeded();
        if (isAutoExpand()) scheduleLayoutRequest();
        else schedulePreviewRestart();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (attached && (width != oldWidth || height != oldHeight) && !isAutoExpand()) {
            schedulePreviewRestart();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        applyConfiguredBehavior();
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        Layout layout = getLayout();
        if (isAutoExpand()) {
            // Auto Expand must expose 100% of the wrapped text immediately. Do not impose the
            // two-line preview cap or the old half-text collapsed height in either Vertical List
            // or Vertical Cards. super.onMeasure() above already measured the full wrapped height.
            expandable = false;
            expanded = false;
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
            // Both Vertical List and Vertical Cards use the same compact stepping preview.
            // Scroll movement pauses the timer; the retry path below restarts it after idle.
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
        if (!isNativeVerticalListRow() && isAutoExpand() && expandable) {
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

    private void applySearchAppearanceIfNeeded() {
        if (SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY) return;
        SmartTextAppearance.applySearchBody(this);
    }

    private void applyConfiguredBehavior() {
        if (!behaviorLocked) return;
        int mode = isNativeVerticalListRow() ? 0 : (isAutoExpand() ? 1 : 2);
        if (appliedBehaviorMode == mode) return;
        appliedBehaviorMode = mode;
        super.setSingleLine(false);
        super.setHorizontallyScrolling(false);
        setHorizontalFadingEdgeEnabled(false);

        // Native Vertical List uses exactly the same long-text semantics as Vertical Cards:
        // Auto Expand shows every line; Auto Scroll keeps a two-line measured viewport over the
        // complete wrapped layout so advancePreview() can step through all lines after idle.
        if (isNativeVerticalListRow()) {
            super.setMaxLines(Integer.MAX_VALUE);
            super.setEllipsize(null);
            setVerticalFadingEdgeEnabled(!isAutoExpand());
            if (!isAutoExpand()) setFadingEdgeLength(dp(8));
            firstVisibleLine = 0;
            expanded = false;
            expandable = false;
            if (isAutoExpand()) scrollTo(0, 0);
            return;
        }

        super.setMaxLines(Integer.MAX_VALUE);
        super.setEllipsize(null);
        setVerticalFadingEdgeEnabled(!isAutoExpand());
        if (!isAutoExpand()) setFadingEdgeLength(dp(8));
    }

    private void scheduleLayoutRequest() {
        removeCallbacks(deferredLayoutRequest);
        if (isNativeVerticalListRow()) return;
        postOnAnimation(deferredLayoutRequest);
    }

    private void schedulePreviewRestart() {
        removeCallbacks(deferredPreviewRestart);
        if (isAutoExpand()) {
            cancelScrollStep();
            firstVisibleLine = 0;
            scrollTo(0, 0);
            return;
        }
        if (LauncherScrollWorkGate.isScrolling() || isNativeListScrolling()) {
            cancelScrollStep();
            postDelayed(deferredPreviewRestart, SCROLL_IDLE_RETRY_MS);
            return;
        }
        postOnAnimation(deferredPreviewRestart);
    }

    private boolean isNativeVerticalListRow() {
        return findNativeListAncestor() != null;
    }

    private AnimatedListView findNativeListAncestor() {
        android.view.ViewParent parent = getParent();
        while (parent instanceof View) {
            if (parent instanceof AnimatedListView) return (AnimatedListView) parent;
            parent = parent.getParent();
        }
        return null;
    }

    private boolean isActuallyVisibleOnScreen() {
        if (LauncherScrollWorkGate.isScrolling()
                || !attached || !isShown() || !hasWindowFocus() || isNativeListScrolling()) return false;
        visibleRect.setEmpty();
        return getLocalVisibleRect(visibleRect)
                && visibleRect.width() > 0
                && visibleRect.height() > 0;
    }

    private boolean isNativeListScrolling() {
        AnimatedListView list = findNativeListAncestor();
        return list != null && list.isScrollInProgress();
    }

    private void cancelScrollStep() {
        removeCallbacks(scrollStep);
        scrollStepScheduled = false;
    }

    private void scheduleScrollStep(long delayMs) {
        if (scrollStepScheduled || !attached || isAutoExpand()) return;
        if (!isShown() || !hasWindowFocus()) {
            cancelScrollStep();
            return;
        }
        long effectiveDelay = (LauncherScrollWorkGate.isScrolling() || isNativeListScrolling())
                ? SCROLL_IDLE_RETRY_MS : delayMs;
        scrollStepScheduled = true;
        postDelayed(scrollStep, effectiveDelay);
    }

    private void restartAutoScroll() {
        cancelScrollStep();
        refreshOverflowMode();
        applyConfiguredBehavior();
        if (isAutoExpand()) {
            firstVisibleLine = 0;
            scrollTo(0, 0);
            return;
        }
        if (LauncherScrollWorkGate.isScrolling() || isNativeListScrolling()) {
            scheduleScrollStep(SCROLL_IDLE_RETRY_MS);
            return;
        }
        firstVisibleLine = 0;
        scrollTo(0, 0);
        if (isActuallyVisibleOnScreen()) scheduleScrollStep(STEP_DELAY_MS);
    }

    private void advancePreview() {
        if (!attached || isAutoExpand()) {
            cancelScrollStep();
            return;
        }
        if (LauncherScrollWorkGate.isScrolling() || isNativeListScrolling()) {
            scheduleScrollStep(SCROLL_IDLE_RETRY_MS);
            return;
        }
        if (!isActuallyVisibleOnScreen()) {
            cancelScrollStep();
            return;
        }
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
