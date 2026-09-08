package fr.neamar.kiss;

/**
 * Tracks only the launcher transition that matters for search cleanup: a verified successful
 * external launch that began while the user had a non-empty search query. The reset is consumed
 * exactly once on the next launcher resume.
 */
final class SearchLaunchReturnState {
    private boolean startedFromSearch;
    private boolean defaultHistoryResetPending;

    void onExternalLaunchStarting(boolean activeSearch) {
        startedFromSearch = activeSearch;
    }

    void onExternalLaunchSucceeded() {
        if (startedFromSearch) defaultHistoryResetPending = true;
        startedFromSearch = false;
    }

    void onExternalLaunchCancelled() {
        startedFromSearch = false;
    }

    boolean consumeDefaultHistoryReset() {
        if (!defaultHistoryResetPending) return false;
        defaultHistoryResetPending = false;
        return true;
    }
}
