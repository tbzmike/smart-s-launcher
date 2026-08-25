package fr.neamar.kiss.forwarder;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import fr.neamar.kiss.MainActivity;

/**
 * Single owner of Vertical Cards viewport policy.
 *
 * Search, keyboard, favorites and workspace resizing are enforced from real layout changes rather
 * than guessed delays. Ordinary provider refreshes still preserve the exact visible card and
 * offset. A real HOME intent is an immediate, one-shot jump to the bottom.
 */
final class VerticalCardViewportController extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final int BOTTOM_TOLERANCE_DP = 6;

    private static final WeakHashMap<MainActivity, WeakReference<VerticalCardViewportController>>
            INSTANCES = new WeakHashMap<>();

    private final SmartCardListForwarder smartCardListForwarder;
    private final VerticalCardViewportPolicy policy = new VerticalCardViewportPolicy();
    private final View.OnLayoutChangeListener geometryListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    onGeometryChanged();

    private ScrollView scroller;
    private ViewGroup column;
    private ViewportSnapshot pendingRebuildSnapshot;
    private boolean bottomPassScheduled;
    private int generation;
    private boolean destroyed;

    VerticalCardViewportController(MainActivity activity,
                                   SmartCardListForwarder smartCardListForwarder) {
        super(activity);
        this.smartCardListForwarder = smartCardListForwarder;
        synchronized (INSTANCES) {
            INSTANCES.put(activity, new WeakReference<>(this));
        }
    }

    void onCreate() {
        resolveViews();
    }

    /** Update the persistent search invariant and arm the first result set of a new query. */
    void onSearchQueryChanged(boolean active, boolean changed) {
        policy.onSearchQueryChanged(active, changed);
        if (changed) {
            generation++;
            pendingRebuildSnapshot = null;
        }
    }

    /** Capture the user's viewport immediately before SmartCardListForwarder replaces its views. */
    void beforeDataSetChanged() {
        generation++;
        pendingRebuildSnapshot = null;
        resolveViews();
        if (!canControlViewport() || mainActivity.adapter == null || mainActivity.adapter.isEmpty()) {
            // Keep a query-transition request armed through empty/intermediate results.
            return;
        }

        if (policy.shouldBottomRebuild()) {
            policy.onBottomRebuildStarted();
            pendingRebuildSnapshot = ViewportSnapshot.bottom();
        } else {
            pendingRebuildSnapshot = captureCurrentViewport();
        }
    }

    /** Restore after the card rebuild and all synchronous decorators have been queued. */
    void afterDataSetChanged() {
        ViewportSnapshot snapshot = pendingRebuildSnapshot;
        pendingRebuildSnapshot = null;
        if (snapshot == null || !canControlViewport()) return;
        scheduleRestore(snapshot, generation);
    }

    /**
     * Capture around an asynchronous visual mutation such as adding the Used-today line. Search
     * and IME invariants override an older manually selected position.
     */
    @Nullable
    ViewportSnapshot captureForContentMutation() {
        resolveViews();
        if (!canControlViewport()) return null;
        return policy.shouldPinGeometry()
                ? ViewportSnapshot.bottom() : captureCurrentViewport();
    }

    void restoreAfterContentMutation(@Nullable ViewportSnapshot snapshot) {
        if (snapshot == null || destroyed) return;
        generation++;
        scheduleRestore(snapshot, generation);
    }

    /** Every actual HOME intent goes to the bottom; no lifecycle inference is involved. */
    void onHomeIntent() {
        generation++;
        pendingRebuildSnapshot = null;
        policy.requestImmediateBottom();
        resolveViews();
        scheduleBottomPass();
        anchorNormalListToLatest();
    }

    /** Forward exact IME state into the layout-driven viewport policy. */
    static void noteKeyboardVisibility(@Nullable MainActivity activity, boolean visible) {
        if (activity == null) return;
        VerticalCardViewportController controller = null;
        synchronized (INSTANCES) {
            WeakReference<VerticalCardViewportController> ref = INSTANCES.get(activity);
            if (ref != null) controller = ref.get();
        }
        if (controller != null) controller.onKeyboardVisibilityChanged(visible);
    }

    void onConfigurationChanged() {
        generation++;
        pendingRebuildSnapshot = null;
        bottomPassScheduled = false;
        policy.resetForConfiguration();
        detachGeometryListeners();
        scroller = null;
        column = null;
        resolveViews();
    }

    void onDestroy() {
        destroyed = true;
        generation++;
        pendingRebuildSnapshot = null;
        bottomPassScheduled = false;
        detachGeometryListeners();
        synchronized (INSTANCES) {
            INSTANCES.remove(mainActivity);
        }
        scroller = null;
        column = null;
    }

    private void onKeyboardVisibilityChanged(boolean visible) {
        policy.setKeyboardVisible(visible);
        if (!visible) return;

        generation++;
        pendingRebuildSnapshot = null;
        resolveViews();
        scheduleBottomPass();
    }

    private boolean isEnabled() {
        return VERTICAL_CARDS.equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private boolean canControlViewport() {
        return !destroyed && isEnabled() && scroller != null && column != null
                && scroller.getVisibility() == View.VISIBLE;
    }

    private void resolveViews() {
        if (destroyed) return;
        ScrollView nextScroller = smartCardListForwarder.getScroller();
        ViewGroup nextColumn = smartCardListForwarder.getColumn();
        if (nextScroller == scroller && nextColumn == column) return;

        detachGeometryListeners();
        scroller = nextScroller;
        column = nextColumn;
        if (scroller != null) scroller.addOnLayoutChangeListener(geometryListener);
        if (column != null) column.addOnLayoutChangeListener(geometryListener);
    }

    private void detachGeometryListeners() {
        if (scroller != null) scroller.removeOnLayoutChangeListener(geometryListener);
        if (column != null) column.removeOnLayoutChangeListener(geometryListener);
    }

    private void onGeometryChanged() {
        if (policy.shouldPinGeometry()) scheduleBottomPass();
    }

    private void scheduleBottomPass() {
        resolveViews();
        if (bottomPassScheduled || !canControlViewport() || !policy.shouldPinGeometry()) return;

        bottomPassScheduled = true;
        final ScrollView target = scroller;
        target.postOnAnimation(() -> {
            bottomPassScheduled = false;
            if (destroyed || target != scroller || !canControlViewport()
                    || !policy.shouldPinGeometry()) {
                return;
            }
            if (scrollToBottom(target)) policy.onBottomApplied();
        });
    }

    private ViewportSnapshot captureCurrentViewport() {
        if (isAtBottom()) return ViewportSnapshot.bottom();
        if (column == null || column.getChildCount() == 0 || scroller == null) {
            return ViewportSnapshot.absolute(0);
        }

        int scrollY = Math.max(0, scroller.getScrollY());
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child.getBottom() > scrollY) {
                return ViewportSnapshot.anchor(i, child.getTop() - scrollY, scrollY);
            }
        }
        return ViewportSnapshot.absolute(scrollY);
    }

    private boolean isAtBottom() {
        if (scroller == null || column == null || column.getChildCount() == 0) return true;
        return maxScrollY(scroller) - scroller.getScrollY() <= toPx(BOTTOM_TOLERANCE_DP);
    }

    private void scheduleRestore(ViewportSnapshot snapshot, int token) {
        final ScrollView target = scroller;
        if (target == null) return;

        // Queue behind the rebuild/decorators, then use the next frame's measured card geometry.
        target.post(() -> {
            if (!isCurrent(token, target)) return;
            target.postOnAnimation(() -> {
                if (!isCurrent(token, target)) return;
                restoreSnapshot(target, snapshot);
            });
        });
    }

    private boolean isCurrent(int token, ScrollView target) {
        return token == generation && target == scroller && canControlViewport();
    }

    private void restoreSnapshot(ScrollView target, ViewportSnapshot snapshot) {
        if (snapshot.bottom || policy.shouldPinGeometry()) {
            if (scrollToBottom(target)) {
                policy.onBottomApplied();
            } else {
                scheduleBottomPass();
            }
            return;
        }

        int targetY = snapshot.absoluteScrollY;
        if (snapshot.childIndex >= 0 && column != null && column.getChildCount() > 0) {
            int index = Math.max(0, Math.min(snapshot.childIndex, column.getChildCount() - 1));
            View child = column.getChildAt(index);
            targetY = child.getTop() - snapshot.topOffset;
        }
        target.scrollTo(target.getScrollX(), clampScrollY(target, targetY));
    }

    private static int clampScrollY(ScrollView target, int requested) {
        int max = maxScrollY(target);
        return Math.max(0, Math.min(requested, max));
    }

    private static int maxScrollY(ScrollView target) {
        View content = target.getChildAt(0);
        if (content == null) return 0;
        int viewportHeight = Math.max(0,
                target.getHeight() - target.getPaddingTop() - target.getPaddingBottom());
        return Math.max(0, content.getHeight() - viewportHeight);
    }

    private boolean scrollToBottom(ScrollView target) {
        View content = target.getChildAt(0);
        if (target.getHeight() <= 0 || content == null || content.getHeight() <= 0) return false;

        int maxScrollY = maxScrollY(target);
        target.scrollTo(target.getScrollX(), maxScrollY);
        return maxScrollY - target.getScrollY() <= toPx(BOTTOM_TOLERANCE_DP);
    }

    private void anchorNormalListToLatest() {
        if (mainActivity.list == null || mainActivity.adapter == null
                || mainActivity.adapter.isEmpty()) {
            return;
        }
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

    static final class ViewportSnapshot {
        final boolean bottom;
        final int childIndex;
        final int topOffset;
        final int absoluteScrollY;

        private ViewportSnapshot(boolean bottom, int childIndex, int topOffset,
                                 int absoluteScrollY) {
            this.bottom = bottom;
            this.childIndex = childIndex;
            this.topOffset = topOffset;
            this.absoluteScrollY = absoluteScrollY;
        }

        static ViewportSnapshot bottom() {
            return new ViewportSnapshot(true, -1, 0, 0);
        }

        static ViewportSnapshot anchor(int childIndex, int topOffset, int absoluteScrollY) {
            return new ViewportSnapshot(false, childIndex, topOffset, absoluteScrollY);
        }

        static ViewportSnapshot absolute(int scrollY) {
            return new ViewportSnapshot(false, -1, 0, scrollY);
        }
    }
}
