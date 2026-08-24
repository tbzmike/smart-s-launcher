package fr.neamar.kiss.forwarder;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import fr.neamar.kiss.MainActivity;

/**
 * Keeps the Vertical Cards history surface anchored to its newest/bottom item when the IME opens.
 *
 * Vertical Cards do not use MainActivity.list; SmartCardListForwarder renders them in a separate
 * ScrollView that is inserted directly into MainActivity.listContainer. The normal KISS ListView
 * can therefore be correctly positioned while the visible cards remain scrolled above the newest
 * item. This controller targets the actual visible Vertical Cards ScrollView without rebuilding the
 * adapter or refreshing launcher data.
 */
final class VerticalCardKeyboardAnchor {
    private static final long IME_SETTLE_DELAY_MS = 220L;

    private VerticalCardKeyboardAnchor() {
    }

    static void onKeyboardVisibilityChanged(MainActivity activity, boolean keyboardVisible) {
        if (!keyboardVisible || activity == null || activity.isFinishing()) return;

        ScrollView scroller = findVisibleVerticalCardsScroller(activity);
        if (scroller == null) return;

        // First pass runs after the next layout produced by adjustResize. The second pass catches
        // the final IME animation frame on keyboards that animate their inset/height over time.
        scroller.post(() -> anchorToBottom(activity, scroller));
        scroller.postDelayed(() -> anchorToBottom(activity, scroller), IME_SETTLE_DELAY_MS);
    }

    private static ScrollView findVisibleVerticalCardsScroller(MainActivity activity) {
        if (!(activity.listContainer instanceof ViewGroup)) return null;

        ViewGroup container = (ViewGroup) activity.listContainer;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof ScrollView && child.getVisibility() == View.VISIBLE) {
                return (ScrollView) child;
            }
        }
        return null;
    }

    private static void anchorToBottom(MainActivity activity, ScrollView scroller) {
        if (activity.isFinishing() || scroller.getVisibility() != View.VISIBLE) return;

        // requestLayout is intentional: the keyboard may have just changed the parent viewport.
        // fullScroll then computes the bottom using the resized height rather than the pre-IME one.
        scroller.requestLayout();
        scroller.fullScroll(View.FOCUS_DOWN);

        // scrollTo(max) makes the final position deterministic even if focus handling inside one
        // of the card controls consumes FOCUS_DOWN.
        View child = scroller.getChildAt(0);
        if (child != null) {
            int maxScrollY = Math.max(0,
                    child.getMeasuredHeight() - scroller.getHeight()
                            + scroller.getPaddingTop() + scroller.getPaddingBottom());
            scroller.scrollTo(scroller.getScrollX(), maxScrollY);
        }
    }
}
