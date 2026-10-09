package fr.neamar.kiss.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fr.neamar.kiss.pojo.Pojo;

class SemanticEmbeddingScorerTest {
    @Test void preparedQueryProducesTheSameScoreAsDirectScoring() {
        Pojo candidate = pojo("JW Library Bible");

        float direct = SemanticEmbeddingScorer.score("scripture", candidate, 128);
        float prepared = SemanticEmbeddingScorer.scorePrepared(
                SemanticEmbeddingScorer.prepareQuery("scripture", 128), candidate);

        assertEquals(direct, prepared, 0.000001f);
        assertTrue(prepared > 0f);
    }

    @Test void preparedQueryCanBeReusedAcrossCandidates() {
        float[] prepared = SemanticEmbeddingScorer.prepareQuery("music", 64);

        float related = SemanticEmbeddingScorer.scorePrepared(prepared, pojo("Spotify Music"));
        float unrelated = SemanticEmbeddingScorer.scorePrepared(prepared, pojo("Calculator"));

        assertTrue(related > unrelated);
    }

    @Test void genericIntentFindsBrandSpecificBankingCandidate() {
        float[] prepared = SemanticEmbeddingScorer.prepareQuery("send money", 256);

        float banking = SemanticEmbeddingScorer.scorePrepared(prepared, pojo("FNB Banking"));
        float calculator = SemanticEmbeddingScorer.scorePrepared(prepared, pojo("Calculator"));

        assertTrue(banking > calculator);
        assertTrue(banking > 0.20f);
    }

    @Test void scriptureIntentFindsJwLibraryWithoutExactWordOverlap() {
        float[] prepared = SemanticEmbeddingScorer.prepareQuery("read scripture", 256);

        float bible = SemanticEmbeddingScorer.scorePrepared(prepared, pojo("JW Library"));
        float unrelated = SemanticEmbeddingScorer.scorePrepared(prepared, pojo("Spotify"));

        assertTrue(bible > unrelated);
    }

    @Test void cachedStoreDescriptionWidensSemanticContext() {
        float[] prepared = SemanticEmbeddingScorer.prepareQuery("scan a document", 256);
        Pojo opaqueName = pojo("Utility Pro");

        float withoutDescription =
                SemanticEmbeddingScorer.scorePrepared(prepared, opaqueName);
        float withDescription =
                SemanticEmbeddingScorer.scorePrepared(
                        prepared,
                        opaqueName,
                        "Scan documents, receipts and photos, then save them as PDF files.");

        assertTrue(withDescription > withoutDescription);
    }

    private static Pojo pojo(String name) {
        Pojo pojo = new Pojo("test://" + name) { };
        pojo.setName(name, false);
        return pojo;
    }
}
