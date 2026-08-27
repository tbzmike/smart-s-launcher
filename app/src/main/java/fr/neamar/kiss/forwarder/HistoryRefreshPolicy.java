package fr.neamar.kiss.forwarder;

/**
 * Decides whether a repeated launcher query can safely skip rebuilding its result set.
 * History is intentionally never skipped solely because the text query is unchanged: launching
 * an item changes persistent recency even when the visible query remains empty.
 */
final class HistoryRefreshPolicy {
    private HistoryRefreshPolicy() { }

    static boolean shouldSkip(boolean initialResumeComplete, boolean sameQuery, boolean historySearch) {
        return initialResumeComplete && sameQuery && !historySearch;
    }
}
