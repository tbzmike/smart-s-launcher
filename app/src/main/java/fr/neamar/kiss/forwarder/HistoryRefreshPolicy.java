package fr.neamar.kiss.forwarder;

/**
 * Decides whether a repeated launcher query can safely skip rebuilding its result set. The clicked
 * history Result is promoted in memory when its launch is persisted; genuine external/provider
 * changes arrive through explicit refresh broadcasts instead of every Activity resume.
 */
final class HistoryRefreshPolicy {
    private HistoryRefreshPolicy() { }

    static boolean shouldSkip(boolean initialResumeComplete, boolean sameQuery, boolean historySearch) {
        return initialResumeComplete && sameQuery;
    }
}
