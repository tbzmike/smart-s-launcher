package fr.neamar.kiss.adapter;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class HistoryRefreshPreserverTest {
    @Test void refreshCannotShrinkVisibleHistoryWhenProviderMissesRows() {
        List<String> merged = HistoryRefreshPreserver.preserveMissing(
                Arrays.asList("A", "B", "C", "D"),
                Arrays.asList("A", "C", "D"),
                value -> value);

        assertThat(merged, contains("A", "B", "C", "D"));
    }

    @Test void refreshedOrderAndNewRowsStayAuthoritative() {
        List<String> merged = HistoryRefreshPreserver.preserveMissing(
                Arrays.asList("A", "B", "C"),
                Arrays.asList("B", "C", "D"),
                value -> value);

        assertThat(merged, contains("A", "B", "C", "D"));
    }

    @Test void emptyRefreshRetainsCurrentWindow() {
        assertThat(HistoryRefreshPreserver.preserveMissing(
                Arrays.asList("A", "B"), Collections.emptyList(), value -> value),
                contains("A", "B"));
    }

    @Test void emptyExistingWindowRemainsEmpty() {
        assertThat(HistoryRefreshPreserver.preserveMissing(
                Collections.emptyList(), Collections.emptyList(), value -> value),
                empty());
    }
}
