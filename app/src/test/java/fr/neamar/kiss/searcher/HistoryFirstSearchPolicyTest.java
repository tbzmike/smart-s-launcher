package fr.neamar.kiss.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class HistoryFirstSearchPolicyTest {
    @Test void databaseStartsAfterFiveSecondsIdle() {
        assertEquals(5_000L, HistoryFirstSearchPolicy.DEEP_SEARCH_IDLE_DELAY_MS);
    }

    @Test void historySeedTracksChangesInsteadOfStickingAtFourHundred() {
        assertEquals(403, HistoryFirstSearchPolicy.seedLimit(403));
        assertEquals(1000, HistoryFirstSearchPolicy.seedLimit(1000));
        assertEquals(200, HistoryFirstSearchPolicy.seedLimit(200));
        assertEquals(0, HistoryFirstSearchPolicy.seedLimit(0));
        assertEquals(0, HistoryFirstSearchPolicy.seedLimit(-1));
    }
}
