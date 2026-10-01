package fr.neamar.kiss.searcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import fr.neamar.kiss.db.HistoryMode;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.RelevanceComparator;

/**
 * Authoritative ordering contract for the visible launcher History timeline.
 *
 * The database RECENCY query returns unique records newest-first. Searcher result queues emit lower
 * relevance first, so assigning larger relevance to earlier database rows produces an
 * oldest-to-newest visible list with the latest clicked item at the final/bottom position.
 */
final class HistoryRecencyOrder {
    static final HistoryMode MODE = HistoryMode.RECENCY;

    private HistoryRecencyOrder() {
    }

    static int relevanceForNewestFirstIndex(int itemCount, int newestFirstIndex) {
        if (itemCount <= 0 || newestFirstIndex < 0 || newestFirstIndex >= itemCount) return 0;
        return itemCount - newestFirstIndex;
    }

    /**
     * Freeze the intended oldest-to-newest presentation into list position. History Pojo objects
     * are shared with query/provider code and their relevance can change after this point; list
     * position must therefore stop depending on that mutable field.
     */
    static List<Pojo> freezeOldestToNewest(List<Pojo> items) {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        List<Pojo> ordered = new ArrayList<>(items);
        ordered.sort(new RelevanceComparator());
        return ordered;
    }
}
