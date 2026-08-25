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
    void homeIsImmediateAndDoesNotArmAnUnrelatedFutureRefresh() {
        VerticalCardViewportPolicy policy = settledPolicy();

        policy.requestImmediateBottom();
        assertThat(policy.shouldPinGeometry(), is(true));
        policy.onBottomApplied();

        assertThat(policy.shouldPinGeometry(), is(false));
        assertThat(policy.shouldBottomRebuild(), is(false));
    }

    @Test
    void passiveRefreshPreservesAManuallySelectedOlderPosition() {
        VerticalCardViewportPolicy policy = settledPolicy();

        assertThat(policy.shouldBottomRebuild(), is(false));
        assertThat(policy.shouldPinGeometry(), is(false));
    }

    @Test
    void eachQueryTransitionForcesItsFirstResultSetToBottom() {
        VerticalCardViewportPolicy policy = settledPolicy();

        policy.onSearchQueryChanged(true, true);
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
    void savedPositionRestoreSettlesOnlyPassiveBottomRequests() {
        VerticalCardViewportPolicy startup = new VerticalCardViewportPolicy();
        assertThat(startup.shouldBottomRebuild(), is(true));
        startup.onPositionRestoreApplied();
        assertThat(startup.shouldBottomRebuild(), is(false));

        VerticalCardViewportPolicy search = settledPolicy();
        search.onSearchQueryChanged(true, true);
        search.onPositionRestoreApplied();
        assertThat(search.shouldBottomRebuild(), is(true));
        assertThat(search.shouldPinGeometry(), is(true));
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
