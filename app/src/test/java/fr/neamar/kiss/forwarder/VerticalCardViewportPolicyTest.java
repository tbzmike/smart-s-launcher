package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class VerticalCardViewportPolicyTest {
    @Test
    void activeSearchPinsEveryRebuildAndGeometryChange() {
        VerticalCardViewportPolicy policy = settledPolicy();

        policy.onSearchQueryChanged(true, true);
        assertThat(policy.shouldBottomRebuild(), is(true));
        policy.onBottomRebuildStarted();
        policy.onBottomApplied();

        assertThat(policy.shouldBottomRebuild(), is(true));
        assertThat(policy.shouldPinGeometry(), is(true));
    }

    @Test
    void deferredBottomIntentPinsExactlyTheNextRebuild() {
        VerticalCardViewportPolicy policy = settledPolicy();
        policy.requestBottomOnNextRebuild();
        assertThat(policy.shouldBottomRebuild(), is(true));
        applyBottomRebuild(policy);
        assertThat(policy.shouldBottomRebuild(), is(false));
        assertThat(policy.shouldPinGeometry(), is(false));
    }

    @Test
    void explicitLatestNavigationStaysPinnedAcrossLaterGeometryAndRebuilds() {
        VerticalCardViewportPolicy policy = settledPolicy();

        policy.requestPersistentBottom();
        assertThat(policy.isPersistentBottomPinned(), is(true));
        assertThat(policy.shouldBottomRebuild(), is(true));
        assertThat(policy.shouldPinGeometry(), is(true));
        policy.onBottomRebuildStarted();
        policy.onBottomApplied();

        assertThat(policy.isPersistentBottomPinned(), is(true));
        assertThat(policy.shouldBottomRebuild(), is(true));
        assertThat(policy.shouldPinGeometry(), is(true));
        assertThat(policy.preventsPositionRestore(), is(true));
    }

    @Test
    void firstRealUserScrollCancelsPersistentHomeBottomPin() {
        VerticalCardViewportPolicy policy = settledPolicy();
        policy.requestPersistentBottom();
        policy.onBottomApplied();

        policy.onUserScrollStarted();

        assertThat(policy.isPersistentBottomPinned(), is(false));
        assertThat(policy.shouldBottomRebuild(), is(false));
        assertThat(policy.shouldPinGeometry(), is(false));
        assertThat(policy.preventsPositionRestore(), is(false));
    }

    @Test
    void passiveRefreshPreservesAManuallySelectedOlderPosition() {
        VerticalCardViewportPolicy policy = settledPolicy();

        assertThat(policy.shouldBottomRebuild(), is(false));
        assertThat(policy.shouldPinGeometry(), is(false));
    }

    @Test
    void eachQueryTransitionForcesItsFirstResultSetToBottomAndCancelsHomePin() {
        VerticalCardViewportPolicy policy = settledPolicy();
        policy.requestPersistentBottom();

        policy.onSearchQueryChanged(true, true);
        assertThat(policy.isPersistentBottomPinned(), is(false));
        applyBottomRebuild(policy);
        policy.onSearchQueryChanged(false, true);

        assertThat(policy.shouldBottomRebuild(), is(true));
        applyBottomRebuild(policy);
        assertThat(policy.shouldBottomRebuild(), is(false));
    }

    @Test
    void keyboardPinsAllViewportResizesUntilItCloses() {
        VerticalCardViewportPolicy policy = settledPolicy();

        policy.setKeyboardVisible(true);
        policy.onBottomApplied();
        assertThat(policy.shouldPinGeometry(), is(true));

        policy.setKeyboardVisible(false);
        assertThat(policy.shouldPinGeometry(), is(false));
    }

    @Test
    void savedPositionRestoreCannotOverrideExplicitLatestPin() {
        VerticalCardViewportPolicy startup = new VerticalCardViewportPolicy();
        assertThat(startup.shouldBottomRebuild(), is(true));
        startup.onPositionRestoreApplied();
        assertThat(startup.shouldBottomRebuild(), is(false));

        VerticalCardViewportPolicy explicit = settledPolicy();
        explicit.requestPersistentBottom();
        explicit.onPositionRestoreApplied();
        assertThat(explicit.shouldBottomRebuild(), is(true));
        assertThat(explicit.shouldPinGeometry(), is(true));
    }

    private static VerticalCardViewportPolicy settledPolicy() {
        VerticalCardViewportPolicy policy = new VerticalCardViewportPolicy();
        applyBottomRebuild(policy);
        return policy;
    }

    private static void applyBottomRebuild(VerticalCardViewportPolicy policy) {
        assertThat(policy.shouldBottomRebuild(), is(true));
        policy.onBottomRebuildStarted();
        policy.onBottomApplied();
    }
}
