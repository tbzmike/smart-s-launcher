package fr.neamar.kiss.forwarder;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

/**
 * Single owner of Vertical Cards viewport policy.
 *
 * Search, keyboard, favorites and workspace resizing are enforced from real layout changes rather
 * than guessed delays. Ordinary provider refreshes still preserve the exact visible card and
 * offset. A first HOME return restores the paused position; a second HOME press while this launcher
 * is already resumed is an immediate, one-shot jump to the bottom.
 */
final class VerticalCardViewportController extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final int BOTTOM_TOLERANCE_DP = 6;
    private static final String PREF_RETURN_PENDING =
            "runtime-vertical-card-return-pending";
    private static final String PREF_RETURN_BOTTOM =
            "runtime-vertical-card-return-bottom";
    private static final String PREF_RETURN_STABLE_ID =
            "runtime-vertical-card-return-stable-id";
    private static final String PREF_RETURN_CHILD_INDEX =
            "runtime-vertical-card-return-child-index";
    private static final String PREF_RETURN_TOP_OFFSET =
            "runtime-vertical-card-return-top-offset";
    private static final String PREF_RETURN_ABSOLUTE_Y =
            "runtime-vertical-card-return-absolute-y";

    private static final WeakHashMap<MainActivity, WeakReference<VerticalCardViewportController>>
            INSTANCES = new WeakHashMap<>();

    private final SmartCardListForwarder smartCardListForwarder;
    private final VerticalCardViewportPolicy policy = new VerticalCardViewportPolicy();
    private final View.OnLayoutChangeListener geometryListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    onGeometryChanged();
    private final ViewTreeObserver.OnScrollChangedListener scrollChangedListener =
            this::scheduleLatestCardControlsUpdate;

    private FrameLayout host;
    private ScrollView scroller;
    private ViewGroup column;
    private ImageButton leftLatestCardButton;
    private ImageButton rightLatestCardButton;
    private ViewportSnapshot pendingRebuildSnapshot;
    private ViewportSnapshot savedReturnSnapshot;
    private boolean returnRestoreRequested;
    private boolean bottomPassScheduled;
    private boolean latestControlsUpdateScheduled;
    private boolean resumed;
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
        installLatestCardControls();
        savedReturnSnapshot = loadSavedReturnSnapshot();
    }

    /** Update the persistent search invariant and arm the first result set of a new query. */
    void onSearchQueryChanged(boolean active, boolean changed) {
        policy.onSearchQueryChanged(active, changed);
        if (active) clearSavedReturnSnapshot();
        if (changed) {
            generation++;
            pendingRebuildSnapshot = null;
        }
        scheduleLatestCardControlsUpdate();
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

        if (returnRestoreRequested && savedReturnSnapshot != null
                && !policy.preventsPositionRestore()) {
            pendingRebuildSnapshot = savedReturnSnapshot;
        } else if (policy.shouldBottomRebuild()) {
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
        if (snapshot != null && canControlViewport()) scheduleRestore(snapshot, generation);
        scheduleLatestCardControlsUpdate();
    }

    /**
     * Capture around an asynchronous visual mutation such as adding the Used-today line. Search
     * and IME invariants override an older manually selected position.
     */
    @Nullable
    ViewportSnapshot captureForContentMutation() {
        resolveViews();
        if (!canControlViewport()) return null;
        if (returnRestoreRequested && savedReturnSnapshot != null
                && !policy.preventsPositionRestore()) {
            return savedReturnSnapshot;
        }
        return policy.shouldPinGeometry()
                ? ViewportSnapshot.bottom() : captureCurrentViewport();
    }

    void restoreAfterContentMutation(@Nullable ViewportSnapshot snapshot) {
        if (snapshot == null || destroyed) return;
        generation++;
        scheduleRestore(snapshot, generation);
    }

    /**
     * Apply the explicit two-stage HOME contract using the activity's verified resumed state.
     * Returning from another app restores the saved history position; Home while already resumed
     * deliberately navigates to the newest bottom card.
     */
    void onHomeIntent(boolean launcherWasForeground) {
        ensureSavedReturnSnapshotLoaded();
        LauncherHomeNavigationPolicy.Action action =
                LauncherHomeNavigationPolicy.actionForHomeIntent(
                        launcherWasForeground, savedReturnSnapshot != null);
        if (action == LauncherHomeNavigationPolicy.Action.RESTORE_LAST_POSITION) {
            requestSavedReturnRestore();
            return;
        }
        if (action == LauncherHomeNavigationPolicy.Action.KEEP_CURRENT_POSITION) return;

        requestExplicitBottom();
    }

    /** Capture the exact history viewport before another activity can replace this launcher. */
    void onLauncherPaused() {
        resumed = false;
        setLatestCardControlsVisible(false);
        resolveViews();
        if (!isRestorableHistoryViewport()) {
            if (!isEnabled()
                    || !TextUtils.isEmpty(mainActivity.searchEditText.getText())
                    || mainActivity.isViewingAllApps()
                    || SearchHandler.getInstance().getLastSearchType() != Searcher.Type.HISTORY) {
                clearSavedReturnSnapshot();
            }
            return;
        }

        savedReturnSnapshot = captureCurrentViewport();
        returnRestoreRequested = false;
        persistSavedReturnSnapshot(savedReturnSnapshot);
    }

    /** Restore after either HOME or Back returns to a launcher instance that had been paused. */
    void onLauncherResumed() {
        resumed = true;
        ensureSavedReturnSnapshotLoaded();
        requestSavedReturnRestore();
        scheduleLatestCardControlsUpdate();
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
        returnRestoreRequested = false;
        bottomPassScheduled = false;
        latestControlsUpdateScheduled = false;
        policy.resetForConfiguration();
        detachViewportListeners();
        removeLatestCardControls();
        host = null;
        scroller = null;
        column = null;
        resolveViews();
        installLatestCardControls();
        scheduleLatestCardControlsUpdate();
    }

    void onDestroy() {
        destroyed = true;
        generation++;
        pendingRebuildSnapshot = null;
        returnRestoreRequested = false;
        bottomPassScheduled = false;
        latestControlsUpdateScheduled = false;
        resumed = false;
        detachViewportListeners();
        removeLatestCardControls();
        synchronized (INSTANCES) {
            INSTANCES.remove(mainActivity);
        }
        host = null;
        scroller = null;
        column = null;
    }

    private void onKeyboardVisibilityChanged(boolean visible) {
        policy.setKeyboardVisible(visible);
        scheduleLatestCardControlsUpdate();
        if (!visible) return;

        clearSavedReturnSnapshot();
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
        FrameLayout nextHost = mainActivity.listContainer instanceof FrameLayout
                ? (FrameLayout) mainActivity.listContainer : null;
        if (nextHost != host) {
            removeLatestCardControls();
            host = nextHost;
            installLatestCardControls();
        }

        ScrollView nextScroller = smartCardListForwarder.getScroller();
        ViewGroup nextColumn = smartCardListForwarder.getColumn();
        if (nextScroller == scroller && nextColumn == column) {
            scheduleLatestCardControlsUpdate();
            return;
        }

        detachViewportListeners();
        scroller = nextScroller;
        column = nextColumn;
        if (scroller != null) scroller.addOnLayoutChangeListener(geometryListener);
        if (column != null) column.addOnLayoutChangeListener(geometryListener);
        if (scroller != null) {
            scroller.getViewTreeObserver().addOnScrollChangedListener(scrollChangedListener);
        }
        scheduleLatestCardControlsUpdate();
    }

    private void detachViewportListeners() {
        if (scroller != null) scroller.removeOnLayoutChangeListener(geometryListener);
        if (column != null) column.removeOnLayoutChangeListener(geometryListener);
        if (scroller != null) {
            ViewTreeObserver observer = scroller.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnScrollChangedListener(scrollChangedListener);
        }
    }

    private void onGeometryChanged() {
        if (returnRestoreRequested && savedReturnSnapshot != null
                && !policy.preventsPositionRestore()) {
            scheduleRestore(savedReturnSnapshot, generation);
        } else if (policy.shouldPinGeometry()) {
            scheduleBottomPass();
        }
        scheduleLatestCardControlsUpdate();
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
            scheduleLatestCardControlsUpdate();
        });
    }

    private void requestExplicitBottom() {
        setLatestCardControlsVisible(false);
        clearSavedReturnSnapshot();
        generation++;
        pendingRebuildSnapshot = null;
        policy.requestImmediateBottom();
        resolveViews();
        scheduleBottomPass();
        anchorNormalListToLatest();
    }

    private void installLatestCardControls() {
        if (destroyed || host == null || leftLatestCardButton != null
                || rightLatestCardButton != null) {
            return;
        }
        leftLatestCardButton = createLatestCardButton(Gravity.START);
        rightLatestCardButton = createLatestCardButton(Gravity.END);
    }

    private ImageButton createLatestCardButton(int horizontalGravity) {
        ImageButton button = new ImageButton(mainActivity);
        button.setImageResource(R.drawable.ic_jump_to_latest_finger);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setBackground(null);
        button.setPadding(toPx(11), toPx(10), toPx(11), toPx(10));
        button.setAlpha(0.96f);
        button.setElevation(toPx(28));
        button.setContentDescription(mainActivity.getString(R.string.main_jump_to_latest));
        button.setVisibility(View.GONE);
        button.setOnClickListener(view -> requestExplicitBottom());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                toPx(52), toPx(64), Gravity.CENTER_VERTICAL | horizontalGravity);
        if (horizontalGravity == Gravity.START) params.leftMargin = toPx(1);
        else params.rightMargin = toPx(1);
        host.addView(button, params);
        return button;
    }

    private void removeLatestCardControls() {
        removeLatestCardControl(leftLatestCardButton);
        removeLatestCardControl(rightLatestCardButton);
        leftLatestCardButton = null;
        rightLatestCardButton = null;
    }

    private static void removeLatestCardControl(@Nullable View control) {
        if (control != null && control.getParent() instanceof ViewGroup) {
            ((ViewGroup) control.getParent()).removeView(control);
        }
    }

    private void scheduleLatestCardControlsUpdate() {
        if (destroyed || latestControlsUpdateScheduled) return;
        View target = scroller != null ? scroller : host;
        if (target == null) return;
        latestControlsUpdateScheduled = true;
        target.postOnAnimation(() -> {
            latestControlsUpdateScheduled = false;
            updateLatestCardControls();
        });
    }

    private void updateLatestCardControls() {
        boolean eligibleHistory = resumed
                && canControlViewport()
                && mainActivity.isViewingSearchResults()
                && TextUtils.isEmpty(mainActivity.searchEditText.getText())
                && SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY;
        boolean automaticBottomPending = policy.shouldPinGeometry()
                || policy.shouldBottomRebuild();
        int count = column == null ? 0 : column.getChildCount();
        int latestCardBottom = 0;
        if (count > 0) {
            latestCardBottom = column.getTop() + column.getChildAt(count - 1).getBottom();
        }
        int visibleViewportBottom = scroller == null ? 0
                : scroller.getScrollY() + scroller.getHeight() - scroller.getPaddingBottom();
        boolean show = LatestCardJumpPolicy.shouldShow(
                eligibleHistory,
                automaticBottomPending,
                count,
                latestCardBottom,
                visibleViewportBottom,
                0);
        setLatestCardControlsVisible(show);
    }

    private void setLatestCardControlsVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        setLatestCardControlVisible(leftLatestCardButton, visibility);
        setLatestCardControlVisible(rightLatestCardButton, visibility);
    }

    private static void setLatestCardControlVisible(@Nullable View control, int visibility) {
        if (control == null || control.getVisibility() == visibility) return;
        control.setVisibility(visibility);
        if (visibility == View.VISIBLE) control.bringToFront();
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
                Object rawTag = child.getTag();
                String stableId = rawTag instanceof String ? (String) rawTag : null;
                return ViewportSnapshot.anchor(
                        stableId, i, child.getTop() - scrollY, scrollY);
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
        boolean applyingSavedReturn = returnRestoreRequested
                && savedReturnSnapshot == snapshot
                && !policy.preventsPositionRestore();
        if (snapshot.bottom || (!applyingSavedReturn && policy.shouldPinGeometry())) {
            if (scrollToBottom(target)) {
                policy.onBottomApplied();
                completeSavedReturnRestoreIfNeeded(snapshot);
            } else {
                scheduleBottomPass();
            }
            return;
        }

        int targetY = snapshot.absoluteScrollY;
        int index = resolveStableAnchorIndex(snapshot);
        if (index >= 0 && column != null) {
            View child = column.getChildAt(index);
            targetY = child.getTop() - snapshot.topOffset;
        }
        target.scrollTo(target.getScrollX(), clampScrollY(target, targetY));
        completeSavedReturnRestoreIfNeeded(snapshot);
        scheduleLatestCardControlsUpdate();
    }

    private int resolveStableAnchorIndex(ViewportSnapshot snapshot) {
        if (column == null || column.getChildCount() == 0) return -1;
        List<String> rebuiltIds = new ArrayList<>(column.getChildCount());
        for (int i = 0; i < column.getChildCount(); i++) {
            Object rawTag = column.getChildAt(i).getTag();
            rebuiltIds.add(rawTag instanceof String ? (String) rawTag : null);
        }
        return StableViewportAnchor.resolveIndex(
                snapshot.stableId, snapshot.childIndex, rebuiltIds);
    }

    private boolean isRestorableHistoryViewport() {
        return canControlViewport()
                && mainActivity.isViewingSearchResults()
                && TextUtils.isEmpty(mainActivity.searchEditText.getText())
                && SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY
                && !policy.preventsPositionRestore();
    }

    private void ensureSavedReturnSnapshotLoaded() {
        if (savedReturnSnapshot == null) {
            savedReturnSnapshot = loadSavedReturnSnapshot();
        }
    }

    private void requestSavedReturnRestore() {
        if (savedReturnSnapshot == null || policy.preventsPositionRestore()) return;
        returnRestoreRequested = true;
        generation++;
        pendingRebuildSnapshot = null;
        resolveViews();
        if (canControlViewport() && column != null && column.getChildCount() > 0) {
            scheduleRestore(savedReturnSnapshot, generation);
        }
    }

    private void completeSavedReturnRestoreIfNeeded(ViewportSnapshot appliedSnapshot) {
        if (!returnRestoreRequested || savedReturnSnapshot != appliedSnapshot
                || policy.preventsPositionRestore()) {
            return;
        }
        policy.onPositionRestoreApplied();
        clearSavedReturnSnapshot();
    }

    @SuppressLint("ApplySharedPref")
    private void persistSavedReturnSnapshot(ViewportSnapshot snapshot) {
        android.content.SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(PREF_RETURN_PENDING, true)
                .putBoolean(PREF_RETURN_BOTTOM, snapshot.bottom)
                .putInt(PREF_RETURN_CHILD_INDEX, snapshot.childIndex)
                .putInt(PREF_RETURN_TOP_OFFSET, snapshot.topOffset)
                .putInt(PREF_RETURN_ABSOLUTE_Y, snapshot.absoluteScrollY);
        if (snapshot.stableId == null) editor.remove(PREF_RETURN_STABLE_ID);
        else editor.putString(PREF_RETURN_STABLE_ID, snapshot.stableId);

        // This tiny state must reach disk before Android is allowed to kill the paused launcher.
        editor.commit();
    }

    @Nullable
    private ViewportSnapshot loadSavedReturnSnapshot() {
        if (!prefs.getBoolean(PREF_RETURN_PENDING, false)) return null;
        return new ViewportSnapshot(
                prefs.getBoolean(PREF_RETURN_BOTTOM, false),
                prefs.getString(PREF_RETURN_STABLE_ID, null),
                prefs.getInt(PREF_RETURN_CHILD_INDEX, -1),
                prefs.getInt(PREF_RETURN_TOP_OFFSET, 0),
                Math.max(0, prefs.getInt(PREF_RETURN_ABSOLUTE_Y, 0)));
    }

    @SuppressLint("ApplySharedPref")
    private void clearSavedReturnSnapshot() {
        boolean storedPending = prefs.getBoolean(PREF_RETURN_PENDING, false);
        boolean hadPendingRestore = savedReturnSnapshot != null || returnRestoreRequested;
        savedReturnSnapshot = null;
        returnRestoreRequested = false;
        pendingRebuildSnapshot = null;
        if (hadPendingRestore) generation++;
        if (!storedPending) return;
        prefs.edit()
                .remove(PREF_RETURN_PENDING)
                .remove(PREF_RETURN_BOTTOM)
                .remove(PREF_RETURN_STABLE_ID)
                .remove(PREF_RETURN_CHILD_INDEX)
                .remove(PREF_RETURN_TOP_OFFSET)
                .remove(PREF_RETURN_ABSOLUTE_Y)
                .commit();
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
        final String stableId;
        final int childIndex;
        final int topOffset;
        final int absoluteScrollY;

        private ViewportSnapshot(boolean bottom, String stableId, int childIndex, int topOffset,
                                 int absoluteScrollY) {
            this.bottom = bottom;
            this.stableId = stableId;
            this.childIndex = childIndex;
            this.topOffset = topOffset;
            this.absoluteScrollY = absoluteScrollY;
        }

        static ViewportSnapshot bottom() {
            return new ViewportSnapshot(true, null, -1, 0, 0);
        }

        static ViewportSnapshot anchor(String stableId, int childIndex, int topOffset,
                                       int absoluteScrollY) {
            return new ViewportSnapshot(
                    false, stableId, childIndex, topOffset, absoluteScrollY);
        }

        static ViewportSnapshot absolute(int scrollY) {
            return new ViewportSnapshot(false, null, -1, 0, scrollY);
        }
    }
}
