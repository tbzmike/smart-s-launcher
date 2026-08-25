package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class LauncherHomeNavigationPolicyTest {
    @Test
    void firstHomeFromAnotherAppRestoresSavedHistoryPosition() {
        assertThat(LauncherHomeNavigationPolicy.actionForHomeIntent(false, true),
                is(LauncherHomeNavigationPolicy.Action.RESTORE_LAST_POSITION));
    }

    @Test
    void secondHomeWhileLauncherIsForegroundGoesToBottom() {
        assertThat(LauncherHomeNavigationPolicy.actionForHomeIntent(true, true),
                is(LauncherHomeNavigationPolicy.Action.GO_TO_BOTTOM));
        assertThat(LauncherHomeNavigationPolicy.actionForHomeIntent(true, false),
                is(LauncherHomeNavigationPolicy.Action.GO_TO_BOTTOM));
    }

    @Test
    void backgroundHomeWithoutSavedStateDoesNotInventAScrollJump() {
        assertThat(LauncherHomeNavigationPolicy.actionForHomeIntent(false, false),
                is(LauncherHomeNavigationPolicy.Action.KEEP_CURRENT_POSITION));
    }
}
