package fr.neamar.kiss.forwarder;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.WeakHashMap;

import fr.neamar.kiss.MainActivity;

/**
 * Single owner of Vertical Cards viewport policy.
 *
 * Ordinary history/provider refreshes preserve the exact visible card and offset. Only an explicit
 * navigation event may force the newest/bottom card into view: a new query/result set, the IME
 * opening, or Home pressed again while the launcher is already foreground.
 */
final class VerticalCardViewportController extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final int BOTTOM_TOLERANCE_DP = 6;

    private static final WeakHashMap<MainActivity, WeakReference<VerticalCardViewportController>>
            INSTANCES = new WeakHashMap<>();

    private final SmartCardListForwarder smartCardListForwarder;
    private ScrollView scroller;
    private ViewGroup column;

    private ViewportSnapshot pendingRebuildSnapshot;
    private boolean forceBottomOnNextRebuild = true;
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

    /** A genuinely new query/result set must expose the strongest/latest bottom card. */
    void requestBottomOnNextRebuild() {
        generation++;
        pendingRebuildSnapshot = null;
        forceBottomOnNextRebuild = true;
    }

    /** Capture the user's viewport immediately before SmartCardListForwarder replaces its views. */
    void beforeDataSetChanged() {
        generation++;
        pendingRebuildSnapshot = null;
        resolveViews();
        if (!canControlViewport() || mainActivity.adapter == null || mainActivity.adapter.isEmpty()) {
            // Keep an explicit-bottom request armed through an empty/intermediate search result.
            return;
        }

        if (forceBottomOnNextRebuild) {
            pendingRebuildSnapshot = ViewportSnapshot.bottom();
            forceBottomOnNextRebuild = false;
        } else {
            pendingRebuildSnapshot = captureCurrentViewport();
        }
    }

    /** Restore after SmartCardListForwarder's posted fullScroll and all card decorators are queued. */
    void afterDataSetChanged() {
        ViewportSnapshot snapshot = pendingRebuildSnapshot;
        pendingRebuildSnapshot = null;
        if (snapshot == null || !canControlViewport()) return;
        scheduleRestore(snapshot, generation);
    }

    /**
     * Capture around an asynchronous visual mutation such as adding the Used-today line. This is
     * intentionally independent of the adapter-rebuild snapshot so a late metadata layout cannot
     * move either an older manually selected position or a bottom-anchored search result.
     */
    @Nullable
    ViewportSnapshot captureForContentMutation() {
        resolveViews();
        if (!canControlViewport()) return null;
        return captureCurrentViewport();
    }

    void restoreAfterContentMutation(@Nullable ViewportSnapshot snapshot) {
        if (snapshot == null || destroyed) return;
        generation++;
        scheduleRestore(snapshot, generation);
    }

    /** A second Home press while already on Home deliberately discards an older scroll position. */
    void onExplicitHomeIntent() {
        generation++;
        pendingRebuildSnapshot = null;
        forceBottomOnNextRebuild = true;
        resolveViews();
        if (canControlViewport()) {
            final ScrollView target = scroller;
            target.post(() -> {
                if (target == scroller && canControlViewport()) scrollToBottom(target);
            });
        }
        anchorNormalListToLatest();
    }

    /** Keyboard anchoring cancels any older pending restore and keeps the next rebuild at bottom. */
    static void noteKeyboardBottom(@Nullable MainActivity activity) {
        if (activity == null) return;
        VerticalCardViewportController controller = null;
        synchronized (INSTANCES) {
            WeakReference<VerticalCardViewportController> ref = INSTANCES.get(activity);
            if (ref != null) controller = ref.get();
        }
        if (controller != null) controller.armExplicitBottom();
    }

    void onConfigurationChanged() {
        generation++;
        pendingRebuildSnapshot = null;
        scroller = null;
        column = null;
        forceBottomOnNextRebuild = true;
        resolveViews();
    }

    void onDestroy() {
        destroyed = true;
        generation++;
        pendingRebuildSnapshot = null;
        synchronized (INSTANCES) {
            INSTANCES.remove(mainActivity);
        }
        scroller = null;
        column = null;
    }

    private void armExplicitBottom() {
        generation++;
        pendingRebuildSnapshot = null;
        forceBottomOnNextRebuild = true;
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

        // SmartCardListForwarder posts its legacy fullScroll during rebuild. Queue once behind it,
        // then restore on the next animation frame after card/favorites/metadata layout is measured.
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
        if (snapshot.bottom) {
            scrollToBottom(target);
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

    private static void scrollToBottom(ScrollView target) {
        target.fullScroll(View.FOCUS_DOWN);
        target.scrollTo(target.getScrollX(), maxScrollY(target));
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
