package fr.neamar.kiss;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class LauncherHomeLifecycleStateTest {
    @Test
    void pausedNewIntentWithoutStopIsASecondHomePress() {
        LauncherHomeLifecycleState state = new LauncherHomeLifecycleState();
        state.onResumeCompleted();

        assertThat(state.launcherWasForegroundBeforeHomeIntent(), is(true));
    }

    @Test
    void stoppedLauncherNewIntentIsAFirstReturnFromAnotherApp() {
        LauncherHomeLifecycleState state = new LauncherHomeLifecycleState();
        state.onResumeCompleted();
        state.onStopped();

        assertThat(state.launcherWasForegroundBeforeHomeIntent(), is(false));
    }

    @Test
    void completedResumeRearmsSecondHomeStage() {
        LauncherHomeLifecycleState state = new LauncherHomeLifecycleState();
        state.onResumeCompleted();
        state.onStopped();
        state.onResumeCompleted();

        assertThat(state.launcherWasForegroundBeforeHomeIntent(), is(true));
    }

    @Test
    void verifiedTranslucentAppLaunchStillUsesFirstHomeRestoreStage() {
        LauncherHomeLifecycleState state = new LauncherHomeLifecycleState();
        state.onResumeCompleted();
        state.onExternalResultLaunched();

        assertThat(state.launcherWasForegroundBeforeHomeIntent(), is(false));
    }
}
