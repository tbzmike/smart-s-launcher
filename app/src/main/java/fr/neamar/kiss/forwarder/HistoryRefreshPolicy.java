package fr.neamar.kiss.forwarder;

/**
 * Decides whether a repeated launcher query can safely skip rebuilding its result set.
 * History is never skipped on Activity resume: the database is authoritative for launch recency,
 * and HistorySearcher must get a chance to put the latest persisted launch at the final row.
 */
final class HistoryRefreshPolicy {
    private HistoryRefreshPolicy() { }

    static boolean shouldSkip(boolean initialResumeComplete, boolean sameQuery,
                              boolean historySearch, boolean emptyQuery) {
        return initialResumeComplete && sameQuery && !historySearch && !emptyQuery;
    }
}
