package fr.neamar.kiss.searcher;

import fr.neamar.kiss.db.HistoryMode;

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
}
