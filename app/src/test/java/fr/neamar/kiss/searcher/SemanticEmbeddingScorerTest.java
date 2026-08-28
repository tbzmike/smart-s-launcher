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

    private static Pojo pojo(String name) {
        Pojo pojo = new Pojo("test://" + name) { };
        pojo.setName(name, false);
        return pojo;
    }
}
