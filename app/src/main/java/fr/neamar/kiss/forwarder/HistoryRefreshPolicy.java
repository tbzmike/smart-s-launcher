package fr.neamar.kiss.forwarder;

/**
 * Decides whether a repeated launcher query can safely skip rebuilding its result set.
 * History is never skipped on Activity resume: the database is authoritative for launch recency,
 * and HistorySearcher must get a chance to put the latest persisted launch at the final row.
 *
 * An empty query is also a state boundary. Even when the cached query string is already empty,
 * Smart S must converge to the configured empty-query screen (History normally, NULL in
 * minimalistic mode). Skipping that transition can leave stale QUERY results visible after HOME.
 */
final class HistoryRefreshPolicy {
    private HistoryRefreshPolicy() { }

    static boolean shouldSkip(boolean initialResumeComplete, boolean sameQuery,
                              boolean historySearch, boolean emptyQuery) {
        return initialResumeComplete && sameQuery && !historySearch && !emptyQuery;
    }
}
