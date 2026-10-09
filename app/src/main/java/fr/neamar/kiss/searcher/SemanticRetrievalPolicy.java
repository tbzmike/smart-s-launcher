package fr.neamar.kiss.searcher;

/**
 * Keeps semantic retrieval fallback semantics explicit and regression-testable.
 */
final class SemanticRetrievalPolicy {
    private SemanticRetrievalPolicy() { }

    static boolean useHnsw(boolean semanticEnabled, boolean hnswEnabled, boolean hnswReady) {
        return semanticEnabled && hnswEnabled && hnswReady;
    }

    static boolean useLegacyFallback(boolean semanticEnabled,
                                     boolean hnswEnabled,
                                     boolean hnswReady) {
        return semanticEnabled && (!hnswEnabled || !hnswReady);
    }
}
