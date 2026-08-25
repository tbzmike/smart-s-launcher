package fr.neamar.kiss.forwarder;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ScrollView;

import java.lang.reflect.Field;

import fr.neamar.kiss.MainActivity;

/**
 * Owns Vertical Cards viewport policy without rebuilding launcher data.
 *
 * Normal history refreshes preserve the exact visible card/offset. If the user was already at the
 * bottom, the bottom remains pinned as new cards arrive. Active search results and an explicit Home
 * press always anchor to the newest/strongest result at the bottom.
 */
final class VerticalCardViewportController extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final long BOTTOM_SETTLE_DELAY_MS = 140L;
    private static final int BOTTOM_TOLERANCE_DP = 6;

    private final SmartCardListForwarder smartCardListForwarder;
    private ScrollView scroller;
    private ViewGroup column;

    private ScrollAnchor pendingAnchor;
    private boolean pendingWasAtBottom;
    private boolean pendingSearch;

    VerticalCardViewportController(MainActivity activity,
                                   SmartCardListForwarder smartCardListForwarder) {
        super(activity);
        this.smartCardListForwarder = smartCardListForwarder;
    }

    void onCreate() {
        resolveViews();
    }

    /** Capture the user's current visual anchor before SmartCardListForwarder replaces its views. */
    void beforeDataSetChanged() {
        resolveViews();
        clearPending();
        if (!isEnabled() || scroller == null || column == null) return;

        pendingSearch = hasActiveSearch();
        pendingWasAtBottom = isAtBottom();
        if (!pendingSearch && !pendingWasAtBottom) {
            pendingAnchor = captureAnchor();
        }
    }

    /** Restore after all card decorators have scheduled their work, overriding rebuild's old snap. */
    void afterDataSetChanged() {
        resolveViews();
        if (!isEnabled() || scroller == null || column == null) {
            clearPending();
            return;
        }

        if (hasActiveSearch() || pendingSearch || pendingWasAtBottom) {
            anchorLatestSettled();
        } else if (pendingAnchor != null) {
            ScrollAnchor anchor = pendingAnchor;
            scroller.post(() -> restoreAnchor(anchor));
        }
        clearPending();
    }

    /** A real Home key/gesture is the one intentional command that discards an older scroll spot. */
    void onExplicitHomeIntent() {
        resolveViews();
        anchorLatestSettled();
        anchorNormalListToLatest();
    }

    void onConfigurationChanged() {
        resolveViews();
    }

    void onDestroy() {
        scroller = null;
        column = null;
        clearPending();
    }

    private boolean isEnabled() {
        return VERTICAL_CARDS.equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private boolean hasActiveSearch() {
        return mainActivity.searchEditText != null
                && !TextUtils.isEmpty(mainActivity.searchEditText.getText());
    }

    private void resolveViews() {
        try {
            Field scrollerField = SmartCardListForwarder.class.getDeclaredField("scroller");
            scrollerField.setAccessible(true);
            Object scrollerValue = scrollerField.get(smartCardListForwarder);
            scroller = scrollerValue instanceof ScrollView ? (ScrollView) scrollerValue : null;

            Field columnField = SmartCardListForwarder.class.getDeclaredField("column");
            columnField.setAccessible(true);
            Object columnValue = columnField.get(smartCardListForwarder);
            column = columnValue instanceof ViewGroup ? (ViewGroup) columnValue : null;
        } catch (ReflectiveOperationException ignored) {
            scroller = null;
            column = null;
        }
    }

    private boolean isAtBottom() {
        if (scroller == null || column == null || column.getChildCount() == 0) return true;
        return maxScrollY() - scroller.getScrollY() <= toPx(BOTTOM_TOLERANCE_DP);
    }

    private ScrollAnchor captureAnchor() {
        if (column == null || column.getChildCount() == 0) return null;
        int scrollY = scroller.getScrollY();
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child.getBottom() > scrollY) {
                return new ScrollAnchor(i, child.getTop() - scrollY);
            }
        }
        int last = column.getChildCount() - 1;
        View child = column.getChildAt(last);
        return new ScrollAnchor(last, child.getTop() - scrollY);
    }

    private void restoreAnchor(ScrollAnchor anchor) {
        if (!isEnabled() || scroller == null || column == null || column.getChildCount() == 0) return;
        int index = Math.max(0, Math.min(anchor.childIndex, column.getChildCount() - 1));
        View child = column.getChildAt(index);
        int targetY = child.getTop() - anchor.topOffset;
        targetY = Math.max(0, Math.min(targetY, maxScrollY()));
        scroller.scrollTo(scroller.getScrollX(), targetY);
    }

    private void anchorLatestSettled() {
        if (!isEnabled() || scroller == null) return;
        scroller.post(this::anchorLatestNow);
        // Search/favorites/IME layout can finish one frame after the card rebuild. Re-anchor once
        // after that resize so the final card is never left underneath the controls.
        scroller.postDelayed(this::anchorLatestNow, BOTTOM_SETTLE_DELAY_MS);
    }

    private void anchorLatestNow() {
        if (!isEnabled() || scroller == null || scroller.getVisibility() != View.VISIBLE) return;
        scroller.requestLayout();
        scroller.fullScroll(View.FOCUS_DOWN);
        scroller.scrollTo(scroller.getScrollX(), maxScrollY());
    }

    private int maxScrollY() {
        if (scroller == null) return 0;
        View content = scroller.getChildAt(0);
        if (content == null) return 0;
        int viewportHeight = Math.max(0,
                scroller.getHeight() - scroller.getPaddingTop() - scroller.getPaddingBottom());
        return Math.max(0, content.getHeight() - viewportHeight);
    }

    private void anchorNormalListToLatest() {
        if (mainActivity.list == null || mainActivity.adapter == null || mainActivity.adapter.isEmpty()) return;
        mainActivity.list.post(() -> {
            if (mainActivity.adapter == null || mainActivity.adapter.isEmpty()) return;
            mainActivity.list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL);
            mainActivity.list.setSelection(mainActivity.adapter.getCount() - 1);
        });
    }

    private int toPx(int valueDp) {
        return Math.max(1, Math.round(
                valueDp * mainActivity.getResources().getDisplayMetrics().density));
    }

    private void clearPending() {
        pendingAnchor = null;
        pendingWasAtBottom = false;
        pendingSearch = false;
    }

    private static final class ScrollAnchor {
        final int childIndex;
        final int topOffset;

        ScrollAnchor(int childIndex, int topOffset) {
            this.childIndex = childIndex;
            this.topOffset = topOffset;
        }
    }
}
