package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class HistoryRefreshPolicyTest {
    @Test
    void unchangedHistoryQueryIsKeptWarmAfterResume() {
        assertThat(HistoryRefreshPolicy.shouldSkip(true, true, true), is(true));
    }

    @Test
    void unchangedNonHistoryQueryCanStillBeSkipped() {
        assertThat(HistoryRefreshPolicy.shouldSkip(true, true, false), is(true));
    }

    @Test
    void changedQueryIsNeverSkipped() {
        assertThat(HistoryRefreshPolicy.shouldSkip(true, false, true), is(false));
        assertThat(HistoryRefreshPolicy.shouldSkip(true, false, false), is(false));
    }
}
