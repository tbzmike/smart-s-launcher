package fr.neamar.kiss.searcher;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import fr.neamar.kiss.db.HistoryMode;
import fr.neamar.kiss.pojo.Pojo;

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

    @Test
    void frozenPresentationOrderSurvivesLaterRelevanceMutation() {
        Pojo oldest = new Pojo("oldest") { };
        Pojo middle = new Pojo("middle") { };
        Pojo newest = new Pojo("newest") { };
        oldest.setName("Oldest");
        middle.setName("Middle");
        newest.setName("Newest");
        oldest.relevance = 1;
        middle.relevance = 2;
        newest.relevance = 3;

        List<Pojo> frozen = HistoryRecencyOrder.freezeOldestToNewest(
                Arrays.asList(newest, oldest, middle));

        // Simulate a concurrent query/provider pass mutating the same shared Pojo objects after
        // History has already decided its display order.
        oldest.relevance = 1000;
        middle.relevance = 500;
        newest.relevance = -100;

        assertThat(Arrays.asList(
                frozen.get(0).id, frozen.get(1).id, frozen.get(2).id),
                is(Arrays.asList("oldest", "middle", "newest")));
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
