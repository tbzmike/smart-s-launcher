package fr.neamar.kiss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fr.neamar.kiss.searcher.Searcher;

class HomeHistoryRecoveryPolicyTest {
    @Test
    void recoversNormalLoadedHomeWhenHistoryIsEmpty() {
        assertTrue(HomeHistoryRecoveryPolicy.shouldRecover(
                true, true, false, true, true, Searcher.Type.HISTORY));
    }

    @Test
    void recoversStartupOrStaleQueryOwnershipAfterHomeIsEmpty() {
        assertTrue(HomeHistoryRecoveryPolicy.shouldRecover(
                true, true, false, true, true, null));
        assertTrue(HomeHistoryRecoveryPolicy.shouldRecover(
                true, true, false, true, true, Searcher.Type.QUERY));
    }

    @Test
    void neverOverridesIntentionalOrStillLoadingEmptyStates() {
        assertFalse(HomeHistoryRecoveryPolicy.shouldRecover(
                false, true, false, true, true, Searcher.Type.HISTORY));
        assertFalse(HomeHistoryRecoveryPolicy.shouldRecover(
                true, true, true, true, true, Searcher.Type.HISTORY));
        assertFalse(HomeHistoryRecoveryPolicy.shouldRecover(
                true, true, false, false, true, Searcher.Type.HISTORY));
        assertFalse(HomeHistoryRecoveryPolicy.shouldRecover(
                true, true, false, true, false, Searcher.Type.HISTORY));
    }

    @Test
    void neverConvertsAllAppsOrTaggedResultsIntoHistory() {
        assertFalse(HomeHistoryRecoveryPolicy.shouldRecover(
                true, false, false, true, true, Searcher.Type.APPLICATION));
        assertFalse(HomeHistoryRecoveryPolicy.shouldRecover(
                true, true, false, true, true, Searcher.Type.TAGGED));
        assertFalse(HomeHistoryRecoveryPolicy.shouldRecover(
                true, true, false, true, true, Searcher.Type.UNTAGGED));
    }
}
