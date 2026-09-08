package fr.neamar.kiss.forwarder;

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
