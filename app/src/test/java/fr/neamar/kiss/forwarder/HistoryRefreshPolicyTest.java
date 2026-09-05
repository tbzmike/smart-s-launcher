package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class HistoryRefreshPolicyTest {
    @Test
    void unchangedHistoryQueryIsRefreshedAfterResume() {
        assertThat(HistoryRefreshPolicy.shouldSkip(true, true, true, true), is(false));
    }

    @Test
    void unchangedNonHistoryQueryCanStillBeSkipped() {
        assertThat(HistoryRefreshPolicy.shouldSkip(true, true, false, false), is(true));
    }

    @Test
    void changedQueryIsNeverSkipped() {
        assertThat(HistoryRefreshPolicy.shouldSkip(true, false, true, false), is(false));
        assertThat(HistoryRefreshPolicy.shouldSkip(true, false, false, false), is(false));
    }

    @Test
    void emptyQueryNeverSkipsStateRestoration() {
        assertThat(HistoryRefreshPolicy.shouldSkip(true, true, false, true), is(false));
    }
}
