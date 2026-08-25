package fr.neamar.kiss.forwarder;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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
    private static final long FIRST_SETTLE_DELAY_MS = 120L;
    private static final long FINAL_SETTLE_DELAY_MS = 320L;

    private VerticalCardKeyboardAnchor() {
    }

    static void onKeyboardVisibilityChanged(MainActivity activity, boolean keyboardVisible) {
        if (!keyboardVisible || activity == null || activity.isFinishing()) return;

        ScrollView scroller = findVisibleVerticalCardsScroller(activity);
        if (scroller == null) return;

        // Invalidate any older rebuild/content-position restoration before the IME changes the
        // viewport. Keyboard-open is an explicit request to expose the newest/strongest result.
        VerticalCardViewportController.noteKeyboardBottom(activity);

        // adjustResize, favorites visibility and the IME animation can complete on different
        // frames. Anchor after each relevant stage so the newest/search-result card ends above the
        // final visible bottom rather than underneath the keyboard or favorites/search controls.
        scroller.post(() -> anchorToBottomIfImeVisible(activity, scroller));
        scroller.postDelayed(
                () -> anchorToBottomIfImeVisible(activity, scroller), FIRST_SETTLE_DELAY_MS);
        scroller.postDelayed(
                () -> anchorToBottomIfImeVisible(activity, scroller), FINAL_SETTLE_DELAY_MS);
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

    private static void anchorToBottomIfImeVisible(MainActivity activity, ScrollView scroller) {
        if (!isImeVisible(activity)) return;
        anchorToBottom(activity, scroller);
    }

    private static boolean isImeVisible(MainActivity activity) {
        View root = activity.findViewById(android.R.id.content);
        WindowInsetsCompat insets = root == null ? null : ViewCompat.getRootWindowInsets(root);
        // A null snapshot means the first IME layout has not been published yet. The callback that
        // invoked us already reported visible, so allow that first anchoring pass.
        return insets == null || insets.isVisible(WindowInsetsCompat.Type.ime());
    }

    private static void anchorToBottom(MainActivity activity, ScrollView scroller) {
        if (activity.isFinishing() || scroller.getVisibility() != View.VISIBLE) return;

        // requestLayout is intentional: the keyboard/favorites may have just changed the parent
        // viewport. fullScroll then computes the bottom from the resized visible result area.
        scroller.requestLayout();
        scroller.fullScroll(View.FOCUS_DOWN);

        // scrollTo(max) makes the final position deterministic even if focus handling inside one
        // of the card controls consumes FOCUS_DOWN.
        View child = scroller.getChildAt(0);
        if (child != null) {
            int viewportHeight = Math.max(0,
                    scroller.getHeight() - scroller.getPaddingTop() - scroller.getPaddingBottom());
            int maxScrollY = Math.max(0, child.getHeight() - viewportHeight);
            scroller.scrollTo(scroller.getScrollX(), maxScrollY);
        }
    }
}
