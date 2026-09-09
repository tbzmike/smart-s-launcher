package fr.neamar.kiss.searcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Select a bounded oldest-to-newest Home History snapshot from cached rows plus the latest launch. */
final class HomeHistoryWindow {
    private HomeHistoryWindow() { }

    static <T> List<T> select(List<T> oldestToNewest, T mostRecent, int maxItems,
                              Function<T, String> idProvider, Predicate<T> eligible) {
        if (maxItems <= 0) return Collections.emptyList();

        List<T> newestFirst = new ArrayList<>(maxItems);
        Set<String> seen = new HashSet<>();
        addIfEligible(newestFirst, seen, mostRecent, maxItems, idProvider, eligible);

        if (oldestToNewest != null) {
            for (int i = oldestToNewest.size() - 1; i >= 0 && newestFirst.size() < maxItems; i--) {
                addIfEligible(newestFirst, seen, oldestToNewest.get(i), maxItems, idProvider, eligible);
            }
        }

        Collections.reverse(newestFirst);
        return newestFirst;
    }

    private static <T> void addIfEligible(List<T> newestFirst, Set<String> seen, T item,
                                          int maxItems, Function<T, String> idProvider,
                                          Predicate<T> eligible) {
        if (item == null || newestFirst.size() >= maxItems || !eligible.test(item)) return;
        String id = idProvider.apply(item);
        if (id == null || id.isEmpty() || !seen.add(id)) return;
        newestFirst.add(item);
    }
}
