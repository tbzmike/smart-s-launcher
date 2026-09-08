from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def create_exact(path, content):
    p = Path(path)
    if p.exists():
        raise SystemExit(f"{path}: refusing to overwrite existing file")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")


# 1) Version: every app change gets a new version. 3.30.60 / 488 becomes baseline only after CI.
path = "app/build.gradle"
replace_once(
    path,
    "        // Smart S Launcher 3.30.59 - bound runtime memory retention\n"
    "        versionCode 487\n"
    "        versionName \"3.30.59\"\n",
    "        // Smart S Launcher 3.30.60 - coherent Vertical Cards refresh and scroll stability\n"
    "        versionCode 488\n"
    "        versionName \"3.30.60\"\n",
)


# 2) Pure frame-stability policy. A deferred history rebuild is allowed only after touch has ended
# and scrollY remains unchanged across two consecutive animation frames. This avoids guessed delays.
create_exact(
    "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardRefreshIdlePolicy.java",
    """package fr.neamar.kiss.forwarder;

/**
 * Frame-based gate for deferred Vertical Cards refreshes.
 *
 * A pending refresh never becomes runnable while a finger is down. After release, scroll movement
 * resets stability; two consecutive animation frames at the same Y establish that a fling has
 * actually settled without relying on an arbitrary millisecond delay.
 */
final class VerticalCardRefreshIdlePolicy {
    private static final int REQUIRED_STABLE_FRAMES = 2;

    private boolean pending;
    private boolean touching;
    private int lastScrollY;
    private int stableFrames;

    void request(int scrollY) {
        pending = true;
        lastScrollY = scrollY;
        stableFrames = 0;
    }

    void onTouchDown() {
        touching = true;
        stableFrames = 0;
    }

    void onTouchReleased(int scrollY) {
        touching = false;
        lastScrollY = scrollY;
        stableFrames = 0;
    }

    boolean shouldProbe() {
        return pending && !touching;
    }

    boolean onAnimationFrame(int scrollY) {
        if (!pending || touching) return false;
        if (scrollY != lastScrollY) {
            lastScrollY = scrollY;
            stableFrames = 0;
            return false;
        }
        stableFrames++;
        if (stableFrames < REQUIRED_STABLE_FRAMES) return false;
        pending = false;
        stableFrames = 0;
        return true;
    }

    void clear() {
        pending = false;
        touching = false;
        stableFrames = 0;
    }
}
""",
)

create_exact(
    "app/src/test/java/fr/neamar/kiss/forwarder/VerticalCardRefreshIdlePolicyTest.java",
    """package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class VerticalCardRefreshIdlePolicyTest {
    @Test
    void pendingRefreshNeverRunsWhileFingerIsDown() {
        VerticalCardRefreshIdlePolicy policy = new VerticalCardRefreshIdlePolicy();
        policy.onTouchDown();
        policy.request(100);

        assertThat(policy.shouldProbe(), is(false));
        assertThat(policy.onAnimationFrame(100), is(false));
        assertThat(policy.onAnimationFrame(100), is(false));
    }

    @Test
    void releaseNeedsTwoStableAnimationFrames() {
        VerticalCardRefreshIdlePolicy policy = new VerticalCardRefreshIdlePolicy();
        policy.onTouchDown();
        policy.request(100);
        policy.onTouchReleased(100);

        assertThat(policy.shouldProbe(), is(true));
        assertThat(policy.onAnimationFrame(100), is(false));
        assertThat(policy.onAnimationFrame(100), is(true));
        assertThat(policy.shouldProbe(), is(false));
    }

    @Test
    void flingMovementResetsStabilityUntilScrollActuallyStops() {
        VerticalCardRefreshIdlePolicy policy = new VerticalCardRefreshIdlePolicy();
        policy.request(100);

        assertThat(policy.onAnimationFrame(118), is(false));
        assertThat(policy.onAnimationFrame(136), is(false));
        assertThat(policy.onAnimationFrame(136), is(false));
        assertThat(policy.onAnimationFrame(150), is(false));
        assertThat(policy.onAnimationFrame(150), is(false));
        assertThat(policy.onAnimationFrame(150), is(true));
    }

    @Test
    void clearCancelsADeferredRefresh() {
        VerticalCardRefreshIdlePolicy policy = new VerticalCardRefreshIdlePolicy();
        policy.request(42);
        policy.clear();

        assertThat(policy.shouldProbe(), is(false));
        assertThat(policy.onAnimationFrame(42), is(false));
    }
}
""",
)


# 3) Vertical Cards renderer: stop rebuilding itself on ACTION_UP. It owns only card construction;
# the manager owns the complete rebuild/decorate/viewport transaction.
path = "app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java"
replace_once(
    path,
    "    private View edgeEffect;\n"
    "    private boolean pendingDataSetRefresh;\n"
    "    private boolean renderedActiveQuery;\n"
    "    private final Runnable activeQueryRebuildRunnable = () -> {\n"
    "        if (isEnabled() && isActiveQuery()) rebuild();\n"
    "    };\n",
    "    private View edgeEffect;\n"
    "    private boolean pendingDataSetRefresh;\n"
    "    private boolean renderedActiveQuery;\n"
    "    private Runnable deferredHistoryRefreshCallback;\n"
    "    private boolean deferredRefreshIdleProbeScheduled;\n"
    "    private final VerticalCardRefreshIdlePolicy deferredRefreshIdlePolicy =\n"
    "            new VerticalCardRefreshIdlePolicy();\n"
    "    private final Runnable activeQueryRebuildRunnable = () -> {\n"
    "        if (isEnabled() && isActiveQuery()) rebuild();\n"
    "    };\n"
    "    private final Runnable deferredRefreshIdleProbe = () -> {\n"
    "        deferredRefreshIdleProbeScheduled = false;\n"
    "        if (scroller == null || !pendingDataSetRefresh || isActiveQuery()) return;\n"
    "        if (deferredRefreshIdlePolicy.onAnimationFrame(scroller.getScrollY())) {\n"
    "            Runnable callback = deferredHistoryRefreshCallback;\n"
    "            if (callback != null) callback.run();\n"
    "            return;\n"
    "        }\n"
    "        scheduleDeferredRefreshIdleProbe();\n"
    "    };\n",
)

old = """    void onDataSetChanged() {
        if (!isEnabled()) return;
        // Search can publish several adapter updates for one input change. Rebuilding every
        // card synchronously for each publication competes with the IME and causes visible
        // typing stalls. Coalesce only active-query rebuilds; idle History keeps its existing
        // deferred-refresh contract.
        boolean activeQuery = isActiveQuery();
        if (column == null || column.getChildCount() == 0
                || (!activeQuery && renderedActiveQuery)) {
            cancelPendingActiveQueryRebuild();
            rebuild();
        } else if (activeQuery) {
            scheduleActiveQueryRebuild();
        } else {
            cancelPendingActiveQueryRebuild();
            pendingDataSetRefresh = true;
        }
    }
"""
new = """    boolean onDataSetChanged() {
        if (!isEnabled()) return false;
        // Search can publish several adapter updates for one input change. Rebuilding every
        // card synchronously for each publication competes with the IME and causes visible
        // typing stalls. Active queries remain coalesced. Idle History marks one pending refresh
        // and lets the manager perform a complete rebuild only after scrolling has actually settled.
        boolean activeQuery = isActiveQuery();
        if (willRebuildSynchronouslyForDataSetChange()) {
            cancelPendingActiveQueryRebuild();
            rebuild();
            return true;
        }
        if (activeQuery) {
            scheduleActiveQueryRebuild();
            return false;
        }

        cancelPendingActiveQueryRebuild();
        pendingDataSetRefresh = true;
        deferredRefreshIdlePolicy.request(scroller == null ? 0 : scroller.getScrollY());
        scheduleDeferredRefreshIdleProbe();
        return false;
    }
"""
replace_once(path, old, new)

replace_once(
    path,
    "    void onDestroy() {\n"
    "        cancelPendingActiveQueryRebuild();\n"
    "        accentCache.clear();\n",
    "    void onDestroy() {\n"
    "        cancelPendingActiveQueryRebuild();\n"
    "        cancelDeferredRefreshIdleProbe();\n"
    "        deferredRefreshIdlePolicy.clear();\n"
    "        deferredHistoryRefreshCallback = null;\n"
    "        accentCache.clear();\n",
)

replace_once(
    path,
    "    LinearLayout getColumn() {\n"
    "        return column;\n"
    "    }\n\n"
    "    private void migrateLegacySelection() {\n",
    "    LinearLayout getColumn() {\n"
    "        return column;\n"
    "    }\n\n"
    "    void setDeferredHistoryRefreshCallback(Runnable callback) {\n"
    "        deferredHistoryRefreshCallback = callback;\n"
    "    }\n\n"
    "    boolean willRebuildSynchronouslyForDataSetChange() {\n"
    "        if (!isEnabled()) return false;\n"
    "        boolean activeQuery = isActiveQuery();\n"
    "        return column == null || column.getChildCount() == 0\n"
    "                || (!activeQuery && renderedActiveQuery);\n"
    "    }\n\n"
    "    boolean hasPendingDataSetRefresh() {\n"
    "        return pendingDataSetRefresh;\n"
    "    }\n\n"
    "    boolean rebuildPendingDataSetRefresh() {\n"
    "        if (!pendingDataSetRefresh || !isEnabled() || isActiveQuery()) return false;\n"
    "        rebuild();\n"
    "        return true;\n"
    "    }\n\n"
    "    void rebuildImmediately() {\n"
    "        if (isEnabled()) rebuild();\n"
    "    }\n\n"
    "    private void migrateLegacySelection() {\n",
)

replace_once(
    path,
    "    private void cancelPendingActiveQueryRebuild() {\n"
    "        if (scroller != null) scroller.removeCallbacks(activeQueryRebuildRunnable);\n"
    "    }\n\n"
    "    private void applySearchFocusIsolation(boolean activeQuery) {\n",
    "    private void cancelPendingActiveQueryRebuild() {\n"
    "        if (scroller != null) scroller.removeCallbacks(activeQueryRebuildRunnable);\n"
    "    }\n\n"
    "    private void scheduleDeferredRefreshIdleProbe() {\n"
    "        if (scroller == null || deferredRefreshIdleProbeScheduled || isActiveQuery()\n"
    "                || !deferredRefreshIdlePolicy.shouldProbe()) return;\n"
    "        deferredRefreshIdleProbeScheduled = true;\n"
    "        scroller.postOnAnimation(deferredRefreshIdleProbe);\n"
    "    }\n\n"
    "    private void cancelDeferredRefreshIdleProbe() {\n"
    "        if (scroller != null) scroller.removeCallbacks(deferredRefreshIdleProbe);\n"
    "        deferredRefreshIdleProbeScheduled = false;\n"
    "    }\n\n"
    "    private void applySearchFocusIsolation(boolean activeQuery) {\n",
)

replace_once(
    path,
    "    private void rebuild() {\n"
    "        if (column == null || mainActivity.adapter == null) return;\n"
    "        cancelPendingActiveQueryRebuild();\n"
    "        pendingDataSetRefresh = false;\n",
    "    private void rebuild() {\n"
    "        if (column == null || mainActivity.adapter == null) return;\n"
    "        cancelPendingActiveQueryRebuild();\n"
    "        cancelDeferredRefreshIdleProbe();\n"
    "        deferredRefreshIdlePolicy.clear();\n"
    "        pendingDataSetRefresh = false;\n",
)

old = """    private void refreshAfterUserTouch() {
        if (!pendingDataSetRefresh || !isEnabled() || isActiveQuery()) return;
        rebuild();
    }

    private final class StableCardScrollView extends ScrollView {
        StableCardScrollView() {
            super(mainActivity);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            boolean handled = super.dispatchTouchEvent(event);
            int action = event.getActionMasked();
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    && pendingDataSetRefresh) {
                post(SmartCardListForwarder.this::refreshAfterUserTouch);
            }
            return handled;
        }
    }
"""
new = """    private final class StableCardScrollView extends ScrollView {
        StableCardScrollView() {
            super(mainActivity);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                deferredRefreshIdlePolicy.onTouchDown();
                cancelDeferredRefreshIdleProbe();
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                deferredRefreshIdlePolicy.onTouchReleased(getScrollY());
                scheduleDeferredRefreshIdleProbe();
            }
            return handled;
        }
    }
"""
replace_once(path, old, new)


# 4) Manager: it becomes the sole owner of a Vertical Cards refresh transaction.
path = "app/src/main/java/fr/neamar/kiss/forwarder/ForwarderManager.java"
replace_once(
    path,
    "        this.verticalCardGroupResizeController = new VerticalCardGroupResizeController(\n"
    "                mainActivity, smartCardListForwarder, verticalCardViewportController);\n",
    "        this.verticalCardGroupResizeController = new VerticalCardGroupResizeController(\n"
    "                mainActivity, smartCardListForwarder, this::rebuildVerticalCardsForExplicitUiChange);\n",
)
replace_once(
    path,
    "        this.communicationHistoryForwarder = new CommunicationHistoryForwarder(mainActivity);\n"
    "        this.widgetPeelController = new WidgetPeelController(mainActivity);\n"
    "    }\n",
    "        this.communicationHistoryForwarder = new CommunicationHistoryForwarder(mainActivity);\n"
    "        this.widgetPeelController = new WidgetPeelController(mainActivity);\n"
    "        this.smartCardListForwarder.setDeferredHistoryRefreshCallback(\n"
    "                this::rebuildDeferredVerticalCards);\n"
    "    }\n",
)
replace_once(
    path,
    "        if (isHistorySearch()) historyVisualEnhancer.onResume();\n"
    "        initialResumeComplete = true;\n",
    "        // Vertical Cards already have dedicated usage/notification enrichment. Running the\n"
    "        // native-list HistoryVisualEnhancer as well duplicates DB/UsageStats work for a hidden\n"
    "        // renderer and can compete with the visible card UI.\n"
    "        if (isHistorySearch() && !verticalCards) historyVisualEnhancer.onResume();\n"
    "        initialResumeComplete = true;\n",
)

old = """    public void onDataSetChanged() {
        widgetsForwarder.onDataSetChanged();
        widgetPeelController.onDataSetChanged();
        historyDisplayForwarder.onDataSetChanged();

        if (isVerticalCardsMode()) {
            verticalCardViewportController.beforeDataSetChanged();
            smartCardListForwarder.onDataSetChanged();
            if (isHistorySearch()) {
                verticalMapsCardForwarder.onDataSetChanged();
                verticalCardGroupResizeController.onDataSetChanged();
                verticalCardNotificationHistoryForwarder.onDataSetChanged();
                verticalCardUsageForwarder.onDataSetChanged();
            }
            verticalCardViewportController.afterDataSetChanged();
        } else if (isSquareMode()) {
            squareUHostFullscreenController.onDataSetChanged();
            squareUStabilityController.onDataSetChanged();
            squareUEdgeBoundsController.onDataSetChanged();
            uNotificationHistoryLongPressForwarder.onDataSetChanged();
        }

        // Launch-stat/live-card enrichment is history decoration. Never run its database/live-data
        // pipeline for ordinary query results, where it only competes with search and scrolling.
        if (isHistorySearch()) historyVisualEnhancer.onDataSetChanged();
        lockedHistoryGestureBridge.onDataSetChanged();
    }
"""
new = """    public void onDataSetChanged() {
        widgetsForwarder.onDataSetChanged();
        widgetPeelController.onDataSetChanged();
        historyDisplayForwarder.onDataSetChanged();

        boolean verticalCards = isVerticalCardsMode();
        boolean verticalCardTreeChanged = false;
        if (verticalCards) {
            boolean synchronousRebuild =
                    smartCardListForwarder.willRebuildSynchronouslyForDataSetChange();
            if (synchronousRebuild) verticalCardViewportController.beforeDataSetChanged();
            verticalCardTreeChanged = smartCardListForwarder.onDataSetChanged();
            if (verticalCardTreeChanged) {
                decorateVerticalCardsAfterRebuild();
                verticalCardViewportController.afterDataSetChanged();
            }
        } else if (isSquareMode()) {
            squareUHostFullscreenController.onDataSetChanged();
            squareUStabilityController.onDataSetChanged();
            squareUEdgeBoundsController.onDataSetChanged();
            uNotificationHistoryLongPressForwarder.onDataSetChanged();
        }

        // Native-list history enrichment is useful for native/square layouts, but Vertical Cards
        // already own equivalent enrichment. Do not run both pipelines against the same history.
        if (isHistorySearch() && !verticalCards) historyVisualEnhancer.onDataSetChanged();
        // Recursive gesture attachment is needed only when the visible Vertical Cards tree changed.
        if (!verticalCards || verticalCardTreeChanged) lockedHistoryGestureBridge.onDataSetChanged();
    }

    private void decorateVerticalCardsAfterRebuild() {
        if (!isHistorySearch()) return;
        verticalMapsCardForwarder.onDataSetChanged();
        verticalCardGroupResizeController.onDataSetChanged();
        verticalCardNotificationHistoryForwarder.onDataSetChanged();
        verticalCardUsageForwarder.onDataSetChanged();
    }

    private void rebuildDeferredVerticalCards() {
        if (!isVerticalCardsMode() || !smartCardListForwarder.hasPendingDataSetRefresh()) return;
        verticalCardViewportController.beforeDataSetChanged();
        if (!smartCardListForwarder.rebuildPendingDataSetRefresh()) return;
        decorateVerticalCardsAfterRebuild();
        verticalCardViewportController.afterDataSetChanged();
        lockedHistoryGestureBridge.onDataSetChanged();
    }

    private void rebuildVerticalCardsForExplicitUiChange() {
        if (!isVerticalCardsMode()) return;
        verticalCardViewportController.beforeDataSetChanged();
        smartCardListForwarder.rebuildImmediately();
        decorateVerticalCardsAfterRebuild();
        verticalCardViewportController.afterDataSetChanged();
        lockedHistoryGestureBridge.onDataSetChanged();
    }
"""
replace_once(path, old, new)


# 5) The resize controller must no longer run a private card-only rebuild that wipes decorators.
path = "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardGroupResizeController.java"
replace_once(
    path,
    "    private final SmartCardListForwarder cardForwarder;\n"
    "    private final VerticalCardViewportController viewportController;\n"
    "    private final SharedPreferences prefs;\n",
    "    private final SmartCardListForwarder cardForwarder;\n"
    "    private final Runnable rebuildCardsCallback;\n"
    "    private final SharedPreferences prefs;\n",
)
replace_once(
    path,
    "    VerticalCardGroupResizeController(MainActivity activity,\n"
    "                                      SmartCardListForwarder cardForwarder,\n"
    "                                      VerticalCardViewportController viewportController) {\n"
    "        this.activity = activity;\n"
    "        this.cardForwarder = cardForwarder;\n"
    "        this.viewportController = viewportController;\n"
    "        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);\n"
    "    }\n",
    "    VerticalCardGroupResizeController(MainActivity activity,\n"
    "                                      SmartCardListForwarder cardForwarder,\n"
    "                                      Runnable rebuildCardsCallback) {\n"
    "        this.activity = activity;\n"
    "        this.cardForwarder = cardForwarder;\n"
    "        this.rebuildCardsCallback = rebuildCardsCallback;\n"
    "        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);\n"
    "    }\n",
)
replace_once(
    path,
    "    private void rebuildCards() {\n"
    "        viewportController.beforeDataSetChanged();\n"
    "        cardForwarder.onDataSetChanged();\n"
    "        viewportController.afterDataSetChanged();\n"
    "    }\n",
    "    private void rebuildCards() {\n"
    "        rebuildCardsCallback.run();\n"
    "    }\n",
)


# 6) A history dataset change can rebind the Maps card from cached data, but must not launch a new
# system location request every time another history item changes.
path = "app/src/main/java/fr/neamar/kiss/forwarder/VerticalMapsCardForwarder.java"
replace_once(
    path,
    "    void onDataSetChanged() {\n"
    "        // Providers normally populate history after onCreate. Start the Maps request when the\n"
    "        // actual Maps card first becomes available rather than depending on a later Home resume.\n"
    "        refreshMapsCard(true);\n"
    "    }\n",
    "    void onDataSetChanged() {\n"
    "        // A card-tree rebuild only needs to reapply the latest cached map state. Location is a\n"
    "        // separate live-data source; requesting a fresh system fix for every history mutation\n"
    "        // creates unrelated main-loop work while the user scrolls or types.\n"
    "        refreshMapsCard(false);\n"
    "    }\n",
)
