package fr.neamar.kiss;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class SearchLaunchReturnStateTest {
    @Test void successfulSearchLaunchRequestsExactlyOneDefaultHistoryReset() {
        SearchLaunchReturnState state = new SearchLaunchReturnState();
        state.onExternalLaunchStarting(true);
        state.onExternalLaunchSucceeded();
        assertThat(state.consumeDefaultHistoryReset(), is(true));
        assertThat(state.consumeDefaultHistoryReset(), is(false));
    }

    @Test void nonSearchLaunchNeverRequestsSearchReset() {
        SearchLaunchReturnState state = new SearchLaunchReturnState();
        state.onExternalLaunchStarting(false);
        state.onExternalLaunchSucceeded();
        assertThat(state.consumeDefaultHistoryReset(), is(false));
    }

    @Test void cancelledSearchLaunchDoesNotResetHistory() {
        SearchLaunchReturnState state = new SearchLaunchReturnState();
        state.onExternalLaunchStarting(true);
        state.onExternalLaunchCancelled();
        assertThat(state.consumeDefaultHistoryReset(), is(false));
    }

    @Test void homeFromQueryRequestsExactlyOneDefaultHistoryReset() {
        SearchLaunchReturnState state = new SearchLaunchReturnState();
        state.onHomeIntent(true);
        assertThat(state.consumeDefaultHistoryReset(), is(true));
        assertThat(state.consumeDefaultHistoryReset(), is(false));
    }

    @Test void homeFromRealHistoryDoesNotRequestSearchReset() {
        SearchLaunchReturnState state = new SearchLaunchReturnState();
        state.onHomeIntent(false);
        assertThat(state.consumeDefaultHistoryReset(), is(false));
    }
}
