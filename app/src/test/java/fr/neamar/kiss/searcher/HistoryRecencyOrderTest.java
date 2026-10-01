package fr.neamar.kiss.searcher;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import fr.neamar.kiss.db.HistoryMode;
import fr.neamar.kiss.pojo.NotificationPojo;
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

    @Test
    void appsShortcutsAndNotificationsShareOneTimestampOrder() {
        Pojo app = new Pojo("app://instagram/Main") { };
        app.setName("Instagram");
        Pojo shortcut = new Pojo("shortcut://maps/search") { };
        shortcut.setName("Maps shortcut");
        NotificationPojo notification = new NotificationPojo(
                "notification://fnb", "za.co.fnb", "FNB", "fnb",
                "notification://fnb", 1, "Payment", "R99 paid", 6_300L);

        Map<String, Long> times = new HashMap<>();
        times.put(app.getHistoryId(), 6_320L);
        times.put(shortcut.getHistoryId(), 6_310L);

        // Deliberately scramble relevance and input order. Neither may influence History.
        app.relevance = -100;
        shortcut.relevance = 9999;
        notification.relevance = 500;

        List<Pojo> ordered = HistoryRecencyOrder.sortTimelineOldestToNewest(
                Arrays.asList(app, notification, shortcut), times, new HashMap<>());

        assertThat(Arrays.asList(
                ordered.get(0).id, ordered.get(1).id, ordered.get(2).id),
                is(Arrays.asList(
                        "notification://fnb",
                        "shortcut://maps/search",
                        "app://instagram/Main")));
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
