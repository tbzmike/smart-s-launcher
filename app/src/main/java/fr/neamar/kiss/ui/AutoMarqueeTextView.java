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

    private boolean behaviorLocked;
    private boolean autoExpand;
    private int appliedBehaviorMode = -1;
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
        }
    }

    /** Called by recycled rows when the saved overflow setting changes. */
    public void refreshConfiguredBehavior() {
        refreshOverflowMode();
        applyConfiguredBehavior();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // A retained/recycled View can survive a Settings round-trip without detaching.
        // Refresh BEFORE measurement, otherwise the old one-line marquee can still win.
        refreshConfiguredBehavior();
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
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
        removeCallbacks(deferredLayoutRequest);
        removeCallbacks(deferredMarqueeRestart);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
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
                super.setSingleLine(false);
                super.setMaxLines(Integer.MAX_VALUE);
                super.setEllipsize(null);
                super.setHorizontallyScrolling(false);
            } else {
                super.setSingleLine(true);
                super.setMaxLines(1);
                super.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                setMarqueeRepeatLimit(-1);
                super.setHorizontallyScrolling(true);
                setHorizontalFadingEdgeEnabled(true);
            }
            return;
        }

        if (isAutoExpand()) {
            super.setSingleLine(false);
            super.setMaxLines(Integer.MAX_VALUE);
            super.setEllipsize(null);
            super.setHorizontallyScrolling(false);
            setMarqueeRepeatLimit(0);
            setHorizontalFadingEdgeEnabled(false);
            setSelected(false);
            return;
        }
        super.setSingleLine(true);
        super.setMaxLines(1);
        super.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        setMarqueeRepeatLimit(-1);
        super.setHorizontallyScrolling(true);
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
            setSelected(false);
            return;
        }
        if (LauncherScrollWorkGate.isScrolling() || isNativeListScrolling()) {
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
        if (LauncherScrollWorkGate.isScrolling() || isNativeListScrolling()
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

    private void restartMarquee() {
        refreshOverflowMode();
        applyConfiguredBehavior();
        if (isAutoExpand()) {
            setSelected(false);
            return;
        }
        if (LauncherScrollWorkGate.isScrolling() || isNativeListScrolling()) {
            scheduleMarqueeRestart();
            return;
        }
        if (!isActuallyVisibleOnScreen()) {
            setSelected(false);
            return;
        }
        setSelected(false);
        setSelected(true);
        invalidate();
    }

    @Override
    public boolean isFocused() {
        // Marquee activation follows actual on-screen visibility. This includes native Vertical List
        // when idle, but remains false during list movement and for off-screen retained cards.
        return isAutoExpand() ? super.isFocused() : isActuallyVisibleOnScreen();
    }

    @Override
    public boolean isSelected() {
        return isAutoExpand() ? super.isSelected() : isActuallyVisibleOnScreen();
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
