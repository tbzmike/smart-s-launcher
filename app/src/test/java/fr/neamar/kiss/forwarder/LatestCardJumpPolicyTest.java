package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class LatestCardJumpPolicyTest {
    @Test
    void olderHistoryShowsJumpControl() {
        assertThat(LatestCardJumpPolicy.shouldShow(
                true, false, 8, 1400, 720, 6), is(true));
    }

    @Test
    void fullyVisibleLatestCardHidesJumpControl() {
        assertThat(LatestCardJumpPolicy.shouldShow(
                true, false, 8, 1400, 1400, 6), is(false));
        assertThat(LatestCardJumpPolicy.shouldShow(
                true, false, 8, 1400, 1399, 0), is(true));
    }

    @Test
    void automaticBottomNavigationNeverCompetesWithControl() {
        assertThat(LatestCardJumpPolicy.shouldShow(
                true, true, 8, 1400, 720, 6), is(false));
    }

    @Test
    void searchEmptyAndNonScrollableStatesHideControl() {
        assertThat(LatestCardJumpPolicy.shouldShow(
                false, false, 8, 1400, 720, 6), is(false));
        assertThat(LatestCardJumpPolicy.shouldShow(
                true, false, 0, 1400, 720, 6), is(false));
        assertThat(LatestCardJumpPolicy.shouldShow(
                true, false, 1, 0, 0, 6), is(false));
    }

    @Test
    void invisibleTrailingMarginDoesNotKeepControlVisible() {
        // The newest card is fully visible at 1400; only six pixels of column margin remain.
        assertThat(LatestCardJumpPolicy.shouldShow(
                true, false, 8, 1400, 1400, 0), is(false));
    }
}
