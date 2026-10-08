package fr.neamar.kiss;

import androidx.annotation.Nullable;

import fr.neamar.kiss.searcher.Searcher;

/**
 * Small deterministic guard for the Home recovery watchdog.
 *
 * Normal Home with providers ready must never remain permanently empty because one lifecycle/search
 * callback was lost. Intentional empty states (minimalistic mode, active query/app list, provider
 * startup) are excluded so recovery cannot override user-visible modes.
 */
final class HomeHistoryRecoveryPolicy {
    private HomeHistoryRecoveryPolicy() { }

    static boolean shouldRecover(boolean providersLoaded,
                                 boolean viewingSearchResults,
                                 boolean minimalisticMode,
                                 boolean emptyQuery,
                                 boolean adapterEmpty,
                                 @Nullable Searcher.Type lastSearchType) {
        if (!providersLoaded
                || !viewingSearchResults
                || minimalisticMode
                || !emptyQuery
                || !adapterEmpty) {
            return false;
        }

        return lastSearchType == null
                || lastSearchType == Searcher.Type.HISTORY
                || lastSearchType == Searcher.Type.QUERY;
    }
}
