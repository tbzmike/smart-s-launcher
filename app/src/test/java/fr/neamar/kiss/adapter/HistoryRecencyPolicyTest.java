package fr.neamar.kiss.adapter;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class HistoryRecencyPolicyTest {
    @Test
    void everyValidNonNewestHistoryItemMovesToBottom() {
        assertThat(HistoryRecencyPolicy.shouldMoveToNewest(0, 4), is(true));
        assertThat(HistoryRecencyPolicy.shouldMoveToNewest(1, 4), is(true));
        assertThat(HistoryRecencyPolicy.shouldMoveToNewest(2, 4), is(true));
    }

    @Test
    void exactClickedObjectIsMovedToFinalPosition() {
        List<String> history = new ArrayList<>(Arrays.asList("oldest", "clicked", "newest"));
        assertThat(HistoryRecencyPolicy.moveSelectedToNewest(history, 1), is(true));
        assertThat(history, is(Arrays.asList("oldest", "newest", "clicked")));
    }

    @Test
    void newestMissingAndSingleItemsDoNotTriggerAListChange() {
        List<String> history = new ArrayList<>(Arrays.asList("oldest", "newest"));
        assertThat(HistoryRecencyPolicy.moveSelectedToNewest(history, 1), is(false));
        assertThat(history, is(Arrays.asList("oldest", "newest")));
        assertThat(HistoryRecencyPolicy.shouldMoveToNewest(-1, 4), is(false));
        assertThat(HistoryRecencyPolicy.shouldMoveToNewest(0, 1), is(false));
    }
}
