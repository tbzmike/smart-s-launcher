package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class VerticalCardRefreshIdlePolicyTest {
    @Test
    void pendingRefreshNeverRunsWhileFingerIsDown() {
        VerticalCardRefreshIdlePolicy policy = new VerticalCardRefreshIdlePolicy();
        policy.onTouchDown();
        policy.request(100, false);

        assertThat(policy.shouldProbe(), is(false));
        assertThat(policy.onAnimationFrame(100), is(false));
        assertThat(policy.onAnimationFrame(100), is(false));
    }

    @Test
    void releaseNeedsTwoStableAnimationFrames() {
        VerticalCardRefreshIdlePolicy policy = new VerticalCardRefreshIdlePolicy();
        policy.onTouchDown();
        policy.request(100, false);
        policy.onTouchReleased(100);

        assertThat(policy.shouldProbe(), is(true));
        assertThat(policy.onAnimationFrame(100), is(false));
        assertThat(policy.onAnimationFrame(100), is(true));
        assertThat(policy.shouldProbe(), is(false));
    }

    @Test
    void flingMovementResetsStabilityUntilScrollActuallyStops() {
        VerticalCardRefreshIdlePolicy policy = new VerticalCardRefreshIdlePolicy();
        policy.request(100, false);

        assertThat(policy.onAnimationFrame(118), is(false));
        assertThat(policy.onAnimationFrame(136), is(false));
        assertThat(policy.onAnimationFrame(136), is(false));
        assertThat(policy.onAnimationFrame(150), is(false));
        assertThat(policy.onAnimationFrame(150), is(false));
        assertThat(policy.onAnimationFrame(150), is(true));
    }

    @Test
    void bottomIntentSurvivesUntilDeferredRefreshConsumesIt() {
        VerticalCardRefreshIdlePolicy policy = new VerticalCardRefreshIdlePolicy();
        policy.request(500, true);

        assertThat(policy.onAnimationFrame(500), is(false));
        assertThat(policy.onAnimationFrame(500), is(true));
        assertThat(policy.consumeKeepBottom(), is(true));
        assertThat(policy.consumeKeepBottom(), is(false));
    }

    @Test
    void laterPendingChangesCannotLoseAnAlreadyCapturedBottomIntent() {
        VerticalCardRefreshIdlePolicy policy = new VerticalCardRefreshIdlePolicy();
        policy.request(500, true);
        policy.request(500, false);

        assertThat(policy.consumeKeepBottom(), is(true));
    }

    @Test
    void clearCancelsADeferredRefreshAndItsBottomIntent() {
        VerticalCardRefreshIdlePolicy policy = new VerticalCardRefreshIdlePolicy();
        policy.request(42, true);
        policy.clear();

        assertThat(policy.shouldProbe(), is(false));
        assertThat(policy.onAnimationFrame(42), is(false));
        assertThat(policy.consumeKeepBottom(), is(false));
    }
}
