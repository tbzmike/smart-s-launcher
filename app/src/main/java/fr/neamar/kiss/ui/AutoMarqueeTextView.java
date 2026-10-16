package fr.neamar.kiss.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.AttributeSet;

import android.widget.TextView;

import fr.neamar.kiss.R;
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
    private static final long OFFSCREEN_RECHECK_MS = 1800L;
    private static final long MARQUEE_FRAME_MS = 65L;
    private static final long MARQUEE_START_HOLD_MS = 650L;
    private static final long MARQUEE_END_HOLD_MS = 950L;

    private boolean behaviorLocked;
    private boolean autoExpand;
    private int appliedBehaviorMode = -1;
    private int marqueeOffset;
    private final Runnable marqueeFrame = this::advanceMarquee;
    private final Rect visibleRect = new Rect();
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
        if (modeChanged) {
            removeCallbacks(marqueeFrame);
            marqueeOffset = 0;
            scrollTo(0, 0);
        }
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
            super.setEllipsize(null);
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
        removeCallbacks(deferredLayoutRequest);
        removeCallbacks(deferredMarqueeRestart);
        removeCallbacks(marqueeFrame);
        marqueeOffset = 0;
        scrollTo(0, 0);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        if (!isAttachedToWindow()) return;
        applySearchAppearanceIfNeeded();
        if (isAutoExpand()) scheduleLayoutRequest();
        else {
            marqueeOffset = 0;
            scrollTo(0, 0);
            scheduleMarqueeRestart();
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (isAttachedToWindow() && width != oldWidth && !isAutoExpand()) {
            marqueeOffset = 0;
            scrollTo(0, 0);
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
            // Framework MARQUEE depends on focus/selection and stops silently when a
            // retained History row is reparented. Own one small, deterministic
            // content scroll instead: no TextView focus or competing animations.
            super.setEllipsize(null);
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
            applyFrameworkTextMode(true);
            setMarqueeRepeatLimit(0);
            setHorizontalFadingEdgeEnabled(false);
            setSelected(false);
            return;
        }
        applyFrameworkTextMode(false);
        setMarqueeRepeatLimit(-1);
        setHorizontalFadingEdgeEnabled(true);
        setSelected(true);
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
            removeCallbacks(marqueeFrame);
            scrollTo(0, 0);
            setSelected(false);
            return;
        }
        if (isNativeListScrolling()) {
            // Never animate text inside an active fling. Keep one cheap delayed retry so native
            // Vertical List marquee starts automatically as soon as motion has really settled.
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
        if (isNativeListScrolling()
                || !isShown() || !isAttachedToWindow() || !hasWindowFocus()) return false;
        visibleRect.setEmpty();
        return getLocalVisibleRect(visibleRect)
                && visibleRect.width() > 0
                && visibleRect.height() > 0;
    }

    private boolean isNativeListScrolling() {
        AnimatedListView list = findNativeListAncestor();
        return list != null && list.isScrollInProgress();
    }

    private int marqueeMaxOffset() {
        if (getWidth() <= 0 || TextUtils.isEmpty(getText())) return 0;
        int width = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (width <= 0) return 0;
        float fullTextWidth = getPaint().measureText(getText().toString());
        return Math.max(0, (int) Math.ceil(fullTextWidth) - width);
    }

    private void restartMarquee() {
        refreshOverflowMode();
        applyConfiguredBehavior();
        removeCallbacks(marqueeFrame);
        if (isAutoExpand()) {
            marqueeOffset = 0;
            scrollTo(0, 0);
            return;
        }
        if (isNativeListScrolling()) {
            removeCallbacks(deferredMarqueeRestart);
            postDelayed(deferredMarqueeRestart, SCROLL_IDLE_RETRY_MS);
            return;
        }
        if (!isActuallyVisibleOnScreen()) {
            // A retained row may be offscreen during an async History update. Do
            // not spin a frame loop while hidden; Android will restart when shown.
            if (isAttachedToWindow() && hasWindowFocus() && isShown()) {
                postDelayed(deferredMarqueeRestart, OFFSCREEN_RECHECK_MS);
            }
            return;
        }
        if (marqueeMaxOffset() == 0) {
            marqueeOffset = 0;
            scrollTo(0, 0);
            return;
        }
        postDelayed(marqueeFrame, marqueeOffset == 0
                ? MARQUEE_START_HOLD_MS : MARQUEE_FRAME_MS);
    }

    private void advanceMarquee() {
        if (isAutoExpand() || !isAttachedToWindow()) return;
        if (isNativeListScrolling() || !isActuallyVisibleOnScreen()) {
            scheduleMarqueeRestart();
            return;
        }
        int max = marqueeMaxOffset();
        if (max <= 0) {
            marqueeOffset = 0;
            scrollTo(0, 0);
            return;
        }
        if (marqueeOffset >= max) {
            marqueeOffset = 0;
            scrollTo(0, 0);
            postDelayed(marqueeFrame, MARQUEE_END_HOLD_MS);
            return;
        }
        int step = Math.max(1, (int) (getResources().getDisplayMetrics().density * 3f));
        marqueeOffset = Math.min(max, marqueeOffset + step);
        scrollTo(marqueeOffset, 0);
        postDelayed(marqueeFrame, MARQUEE_FRAME_MS);
    }

    /** Used by Android regression tests to verify real movement, not just settings. */
    public int getCurrentMarqueeOffset() {
        return marqueeOffset;
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible && !isAutoExpand()) scheduleMarqueeRestart();
        else if (!isVisible) removeCallbacks(marqueeFrame);
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
