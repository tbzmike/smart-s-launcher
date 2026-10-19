package fr.neamar.kiss.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.AttributeSet;

import android.widget.TextView;

import fr.neamar.kiss.R;
import fr.neamar.kiss.utils.LauncherScrollWorkGate;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

/**
 * Long-text view shared by Smart S result layouts.
 * Auto-scroll preserves the compact one-line marquee. Auto-expand disables marquee work and lets
 * the text wrap to unlimited lines so its parent tile can grow until the complete text is visible.
 */
@SuppressLint("AppCompatCustomView")
public class AutoMarqueeTextView extends TextView {
    private static final long SCROLL_IDLE_RETRY_MS = 320L;
    // The system marquee can remain selected but stationary in recycled History rows.
    // Drive compact, clipped text deterministically with one main-thread callback per
    // actually visible overflowing TextView; no background worker or perpetual polling.
    private static final long HORIZONTAL_FRAME_MS = 80L;
    private static final long HORIZONTAL_START_MS = 850L;
    private static final long HORIZONTAL_RESET_MS = 1500L;

    private boolean behaviorLocked;
    private boolean autoExpand;
    private int appliedBehaviorMode = -1;
    private final Rect visibleRect = new Rect();
    private boolean horizontalFramePending;
    private final Runnable horizontalFrame = () -> {
        horizontalFramePending = false;
        advanceHorizontalText();
    };
    private final Runnable deferredLayoutRequest = () -> {
        if (isAttachedToWindow()) requestLayout();
    };
    private final Runnable deferredMarqueeRestart = () -> {
        if (isAttachedToWindow()) restartMarquee();
    };

    public AutoMarqueeTextView(Context context) {
        super(context);
        init();
    }

    public AutoMarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AutoMarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        behaviorLocked = false;
        autoExpand = TextOverflowMode.isAutoExpandForHistory(getContext());
        setFocusable(false);
        setFocusableInTouchMode(false);
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
            // One retained row can move between a tall multi-line StaticLayout and
            // a compact marquee without being detached or having its text replaced.
            // Force measurement to rebuild the layout for the newly selected mode.
            removeCallbacks(deferredLayoutRequest);
            removeCallbacks(deferredMarqueeRestart);
            stopHorizontalText();
            setSelected(false);
            requestLayout();
            invalidate();
        }
    }

    /** Called by recycled rows when the saved overflow setting changes. */
    public void refreshConfiguredBehavior() {
        boolean modeChanged = autoExpand != TextOverflowMode.isAutoExpandForHistory(getContext());
        refreshOverflowMode();
        applyConfiguredBehavior();
        if (modeChanged && getText() != null) {
            // Rebuild TextView's cached StaticLayout/BoringLayout. Merely altering
            // maxLines and ellipsize leaves a retained marquee's old one-line
            // Layout in place on some Android versions after returning to Expand.
            CharSequence current = getText();
            setText(null);
            setText(current);
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // A retained/recycled View can survive a Settings round-trip without detaching.
        // Refresh BEFORE measurement, otherwise the old one-line marquee can still win.
        refreshConfiguredBehavior();
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (!isAutoExpand()) {
            // TextView may retain the full wrapped Layout briefly after switching
            // from Auto Expand. A compact tile still must occupy one text line,
            // even before the next attach/window-focus lifecycle callback.
            int lineHeight = Math.max(1, getLineHeight());
            int compactHeight = getCompoundPaddingTop() + getCompoundPaddingBottom() + lineHeight;
            int heightMode = MeasureSpec.getMode(heightMeasureSpec);
            if (heightMode == MeasureSpec.EXACTLY) {
                compactHeight = MeasureSpec.getSize(heightMeasureSpec);
            } else if (heightMode == MeasureSpec.AT_MOST) {
                compactHeight = Math.min(compactHeight, MeasureSpec.getSize(heightMeasureSpec));
            }
            setMeasuredDimension(getMeasuredWidth(), Math.min(getMeasuredHeight(), compactHeight));
        }
    }

    @Override
    public void setSingleLine(boolean singleLine) {
        if (behaviorLocked) {
            refreshConfiguredBehavior();
            return;
        }
        super.setSingleLine(singleLine);
    }

    @Override
    public void setMaxLines(int maxLines) {
        if (behaviorLocked) {
            refreshOverflowMode();
            super.setMaxLines(isAutoExpand() ? Integer.MAX_VALUE : 1);
            return;
        }
        super.setMaxLines(maxLines);
    }

    @Override
    public void setEllipsize(TextUtils.TruncateAt where) {
        if (behaviorLocked) {
            refreshOverflowMode();
            super.setEllipsize(isAutoExpand() ? null : TextUtils.TruncateAt.MARQUEE);
            return;
        }
        super.setEllipsize(where);
    }

    @Override
    public void setHorizontallyScrolling(boolean whether) {
        if (behaviorLocked) {
            refreshOverflowMode();
            super.setHorizontallyScrolling(!isAutoExpand());
            return;
        }
        super.setHorizontallyScrolling(whether);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshOverflowMode();
        applySearchAppearanceIfNeeded();
        applyConfiguredBehavior();
        if (isAutoExpand()) scheduleLayoutRequest();
        else scheduleMarqueeRestart();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopHorizontalText();
        removeCallbacks(deferredLayoutRequest);
        removeCallbacks(deferredMarqueeRestart);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        stopHorizontalText();
        if (!isAttachedToWindow()) return;
        applySearchAppearanceIfNeeded();
        if (isAutoExpand()) scheduleLayoutRequest();
        else scheduleMarqueeRestart();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (isAttachedToWindow() && width != oldWidth && !isAutoExpand()) {
            scheduleMarqueeRestart();
        }
    }

    private void applySearchAppearanceIfNeeded() {
        if (SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY) return;
        int id = getId();
        if (id == R.id.item_app_tag
                || id == R.id.item_shortcut_tag
                || id == R.id.item_contact_phone
                || id == R.id.item_contact_nickname
                || id == R.id.item_notification_text
                || id == R.id.item_notification_title
                || id == R.id.item_communication_meta
                || id == R.id.item_communication_body) {
            SmartTextAppearance.applySearchBody(this);
        } else {
            SmartTextAppearance.applySearchTitle(this);
        }
    }

    /**
     * Android TextView's setSingleLine calls other virtual setters internally.
     * Temporarily release the policy overrides while applying an entire mode,
     * then lock it again so an arbitrary adapter setter cannot silently undo it.
     */
    private void applyFrameworkTextMode(boolean expanded) {
        behaviorLocked = false;
        try {
            super.setSingleLine(!expanded);
            super.setMaxLines(expanded ? Integer.MAX_VALUE : 1);
            super.setEllipsize(expanded ? null : TextUtils.TruncateAt.MARQUEE);
            super.setHorizontallyScrolling(!expanded);
        } finally {
            behaviorLocked = true;
        }
    }

    private void applyConfiguredBehavior() {
        if (!behaviorLocked) return;
        int mode = isNativeVerticalListRow() ? 0 : (isAutoExpand() ? 1 : 2);
        if (appliedBehaviorMode == mode) return;
        appliedBehaviorMode = mode;

        // Native Vertical List still avoids marquee work during an active fling, but Auto Scroll
        // must actually scroll once the list is idle. Auto Expand remains full wrapped text.
        if (isNativeVerticalListRow()) {
            setSelected(false);
            if (isAutoExpand()) {
                setMarqueeRepeatLimit(0);
                setHorizontalFadingEdgeEnabled(false);
                applyFrameworkTextMode(true);
            } else {
                applyFrameworkTextMode(false);
                setMarqueeRepeatLimit(-1);
                setHorizontalFadingEdgeEnabled(true);
            }
            return;
        }

        if (isAutoExpand()) {
            stopHorizontalText();
            applyFrameworkTextMode(true);
            setMarqueeRepeatLimit(0);
            setHorizontalFadingEdgeEnabled(false);
            setSelected(false);
            return;
        }
        applyFrameworkTextMode(false);
        // Android's built-in marquee is focus/selection sensitive and can stay
        // completely still after the view was detached/rebound. Keep the same
        // compact one-line layout but use controlled horizontal scrolling instead.
        setMarqueeRepeatLimit(0);
        setHorizontalFadingEdgeEnabled(true);
        setSelected(false);
    }

    private void scheduleLayoutRequest() {
        removeCallbacks(deferredLayoutRequest);
        // ListView's normal row measurement already handles the new text. Posting another
        // requestLayout from a recycled row creates a second layout pass and visible settling.
        if (isNativeVerticalListRow()) return;
        postOnAnimation(deferredLayoutRequest);
    }

    private void scheduleMarqueeRestart() {
        removeCallbacks(deferredMarqueeRestart);
        if (isAutoExpand()) {
            setSelected(false);
            return;
        }
        if (isNativeListScrolling()) {
            // Never animate text inside an active fling. Keep one cheap delayed retry so native
            // Vertical List marquee starts automatically as soon as motion has really settled.
            stopHorizontalText();
            setSelected(false);
            postDelayed(deferredMarqueeRestart, SCROLL_IDLE_RETRY_MS);
            return;
        }
        postOnAnimation(deferredMarqueeRestart);
    }

    private boolean isNativeVerticalListRow() {
        return findNativeListAncestor() != null;
    }

    private AnimatedListView findNativeListAncestor() {
        android.view.ViewParent parent = getParent();
        while (parent instanceof android.view.View) {
            if (parent instanceof AnimatedListView) return (AnimatedListView) parent;
            parent = parent.getParent();
        }
        return null;
    }

    private boolean isActuallyVisibleOnScreen() {
        // The global scroll work gate can remain active because another History
        // renderer is moving. Only the native List row's *own* scrolling state
        // should block this text view. The renderer calls refresh on real idle.
        if (isNativeListScrolling() || !isShown() || !isAttachedToWindow()) return false;
        visibleRect.setEmpty();
        return getLocalVisibleRect(visibleRect)
                && visibleRect.width() > 0
                && visibleRect.height() > 0;
    }

    private boolean isNativeListScrolling() {
        AnimatedListView list = findNativeListAncestor();
        return list != null && list.isScrollInProgress();
    }

    private void restartMarquee() {
        refreshOverflowMode();
        applyConfiguredBehavior();
        if (isAutoExpand()) {
            stopHorizontalText();
            return;
        }
        if (isNativeListScrolling()) {
            stopHorizontalText();
            return; // Renderer re-arms after actual scroll idle.
        }
        if (!isActuallyVisibleOnScreen()) {
            stopHorizontalText();
            return;
        }
        armHorizontalText(HORIZONTAL_START_MS);
    }

    private int horizontalOverflowPx() {
        if (getWidth() <= 0 || getText() == null || getText().length() == 0) return 0;
        int available = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (available <= 0) return 0;
        float measured = getPaint().measureText(getText().toString());
        if (getLayout() != null && getLayout().getLineCount() > 0) {
            measured = Math.max(measured, getLayout().getLineWidth(0));
        }
        return Math.max(0, (int) Math.ceil(measured - available));
    }

    private void stopHorizontalText() {
        removeCallbacks(horizontalFrame);
        horizontalFramePending = false;
        if (getScrollX() != 0) scrollTo(0, 0);
    }

    private void armHorizontalText(long delayMs) {
        if (horizontalFramePending || isAutoExpand() || !isActuallyVisibleOnScreen()
                || !hasWindowFocus() || horizontalOverflowPx() < dp(3)) return;
        horizontalFramePending = true;
        postDelayed(horizontalFrame, delayMs);
    }

    private void advanceHorizontalText() {
        if (isAutoExpand() || !isActuallyVisibleOnScreen() || !hasWindowFocus()) {
            stopHorizontalText();
            return;
        }
        int maxScrollX = horizontalOverflowPx();
        if (maxScrollX < dp(3)) { stopHorizontalText(); return; }
        int current = Math.max(0, getScrollX());
        if (current >= maxScrollX) {
            scrollTo(0, 0);
            armHorizontalText(HORIZONTAL_RESET_MS);
            return;
        }
        scrollTo(Math.min(maxScrollX, current + Math.max(1, dp(3))), 0);
        armHorizontalText(HORIZONTAL_FRAME_MS);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean isFocused() {
        // The controlled offset animation does not need Android's focus-based
        // marquee; claiming focus here starts a second conflicting animation.
        return super.isFocused();
    }

    @Override
    public boolean isSelected() {
        // TextView.setSelected(true) consults isSelected() to decide whether to
        // activate its internal marquee. Reporting 'selected' merely because this
        // View is visible makes that setter a no-op and leaves Auto Scroll frozen.
        return super.isSelected();
    }

    /** Retry activation when a retained card becomes visible after paging or scrolling. */
    public void refreshAutoScrollIfVisible() {
        if (isAutoExpand() || !isAttachedToWindow() || !isShown()
                || isNativeListScrolling()) return;
        visibleRect.setEmpty();
        if (!getLocalVisibleRect(visibleRect) || visibleRect.isEmpty()) return;
        // This works when the view was already selected but its old framework
        // marquee never started after card recycling or a Home resume.
        armHorizontalText(HORIZONTAL_START_MS);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (isShown() && !isAutoExpand()) scheduleMarqueeRestart();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            refreshOverflowMode();
            applyConfiguredBehavior();
            if (!isAutoExpand()) scheduleMarqueeRestart();
        }
    }
}
