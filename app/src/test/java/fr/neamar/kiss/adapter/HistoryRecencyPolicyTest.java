package fr.neamar.kiss.adapter;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class HistoryRecencyPolicyTest {
    @Test
    void everyValidNonNewestHistoryItemMovesToBottom() {
        assertThat(HistoryRecencyPolicy.shouldMoveToNewest(0, 4), is(true));
        assertThat(HistoryRecencyPolicy.shouldMoveToNewest(1, 4), is(true));
        assertThat(HistoryRecencyPolicy.shouldMoveToNewest(2, 4), is(true));
    }

    @Test
    void newestMissingAndSingleItemsDoNotTriggerAListChange() {
        assertThat(HistoryRecencyPolicy.shouldMoveToNewest(3, 4), is(false));
        assertThat(HistoryRecencyPolicy.shouldMoveToNewest(-1, 4), is(false));
        assertThat(HistoryRecencyPolicy.shouldMoveToNewest(0, 1), is(false));
    }
}
