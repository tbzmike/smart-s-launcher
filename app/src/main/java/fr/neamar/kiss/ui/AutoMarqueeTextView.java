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

    @Override
    public void setSingleLine(boolean singleLine) {
        if (behaviorLocked) {
            applyConfiguredBehavior();
            return;
        }
        super.setSingleLine(singleLine);
    }

    @Override
    public void setMaxLines(int maxLines) {
        if (behaviorLocked) {
            super.setMaxLines(isAutoExpand() ? Integer.MAX_VALUE : 1);
            return;
        }
        super.setMaxLines(maxLines);
    }

    @Override
    public void setEllipsize(TextUtils.TruncateAt where) {
        if (behaviorLocked) {
            super.setEllipsize(isAutoExpand() ? null : TextUtils.TruncateAt.MARQUEE);
            return;
        }
        super.setEllipsize(where);
    }

    @Override
    public void setHorizontallyScrolling(boolean whether) {
        if (behaviorLocked) {
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
                || id == R.id.item_notification_title) {
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

        // Native Vertical List rows are recycled during a fling. Keep their text geometry and
        // horizontal position completely static: no marquee, no selected-state restart, no
        // after-scroll animation. Vertical Cards/3D Wheel retain the configured behavior.
        if (isNativeVerticalListRow()) {
            // Vertical List is deliberately fixed-geometry. Auto Expand and marquee are disabled
            // here because either can change row measurement/position during or after a fling.
            setMarqueeRepeatLimit(0);
            setHorizontalFadingEdgeEnabled(false);
            setSelected(false);
            super.setSingleLine(true);
            super.setMaxLines(1);
            super.setEllipsize(TextUtils.TruncateAt.END);
            super.setHorizontallyScrolling(false);
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
        if (isNativeVerticalListRow()) {
            setSelected(false);
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
        if (!isShown() || !isAttachedToWindow() || !hasWindowFocus()) return false;
        visibleRect.setEmpty();
        return getLocalVisibleRect(visibleRect)
                && visibleRect.width() > 0
                && visibleRect.height() > 0;
    }

    private void restartMarquee() {
        applyConfiguredBehavior();
        if (isAutoExpand()) return;
        setSelected(false);
        setSelected(true);
        invalidate();
    }

    @Override
    public boolean isFocused() {
        if (isNativeVerticalListRow()) return false;
        // ScrollView keeps off-screen cards attached. isShown() alone therefore marked every row as
        // marquee-active and continuously invalidated text that was nowhere near the viewport.
        return isAutoExpand() ? super.isFocused() : isActuallyVisibleOnScreen();
    }

    @Override
    public boolean isSelected() {
        if (isNativeVerticalListRow()) return false;
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
