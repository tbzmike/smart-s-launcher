package fr.neamar.kiss.searcher;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import fr.neamar.kiss.db.HistoryMode;

class HistoryRecencyOrderTest {
    private static final class RankedItem {
        final String name;
        final int relevance;

        RankedItem(String name, int relevance) {
            this.name = name;
            this.relevance = relevance;
        }
    }

    @Test
    void visibleHistoryAlwaysUsesRecency() {
        assertThat(HistoryRecencyOrder.MODE, is(HistoryMode.RECENCY));
    }

    @Test
    void clickingPhotosThenTwitterProducesExactMruChainAtBottom() {
        List<String> afterPhotosClickNewestFirst = Arrays.asList(
                "Photos", "Twitter", "Item6", "Item4", "Item3", "Item2", "Item1");
        assertThat(toVisibleOldestToNewest(afterPhotosClickNewestFirst), is(Arrays.asList(
                "Item1", "Item2", "Item3", "Item4", "Item6", "Twitter", "Photos")));

        List<String> afterTwitterClickNewestFirst = Arrays.asList(
                "Twitter", "Photos", "Item6", "Item4", "Item3", "Item2", "Item1");
        assertThat(toVisibleOldestToNewest(afterTwitterClickNewestFirst), is(Arrays.asList(
                "Item1", "Item2", "Item3", "Item4", "Item6", "Photos", "Twitter")));
    }

    private static List<String> toVisibleOldestToNewest(List<String> newestFirst) {
        List<RankedItem> ranked = new ArrayList<>(newestFirst.size());
        for (int i = 0; i < newestFirst.size(); i++) {
            ranked.add(new RankedItem(newestFirst.get(i),
                    HistoryRecencyOrder.relevanceForNewestFirstIndex(newestFirst.size(), i)));
        }
        ranked.sort(Comparator.comparingInt(item -> item.relevance));
        List<String> visible = new ArrayList<>(ranked.size());
        for (RankedItem item : ranked) visible.add(item.name);
        return visible;
    }
}
