package fr.neamar.kiss.searcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SemanticRetrievalPolicyTest {
    @Test
    void readyHnswOwnsSemanticRetrieval() {
        assertTrue(SemanticRetrievalPolicy.useHnsw(true, true, true));
        assertFalse(SemanticRetrievalPolicy.useLegacyFallback(true, true, true));
    }

    @Test
    void warmingHnswFallsBackToLegacySemanticCoverage() {
        assertFalse(SemanticRetrievalPolicy.useHnsw(true, true, false));
        assertTrue(SemanticRetrievalPolicy.useLegacyFallback(true, true, false));
    }

    @Test
    void disabledHnswRetainsExistingSemanticSearch() {
        assertFalse(SemanticRetrievalPolicy.useHnsw(true, false, false));
        assertTrue(SemanticRetrievalPolicy.useLegacyFallback(true, false, false));
    }

    @Test
    void semanticOffRunsNeitherSemanticEngine() {
        assertFalse(SemanticRetrievalPolicy.useHnsw(false, true, true));
        assertFalse(SemanticRetrievalPolicy.useLegacyFallback(false, true, false));
    }
}
