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
        float[] prepared = SemanticEmbeddingScorer.prepareQuery("send money", 384);

        float banking = SemanticEmbeddingScorer.scorePrepared(prepared, pojo("FNB Banking"));
        float calculator = SemanticEmbeddingScorer.scorePrepared(prepared, pojo("Calculator"));

        assertTrue(banking > calculator);
        assertTrue(banking > 0.20f);
    }

    @Test void scriptureIntentFindsJwLibraryWithoutExactWordOverlap() {
        float[] prepared = SemanticEmbeddingScorer.prepareQuery("read scripture", 384);

        float bible = SemanticEmbeddingScorer.scorePrepared(prepared, pojo("JW Library"));
        float unrelated = SemanticEmbeddingScorer.scorePrepared(prepared, pojo("Spotify"));

        assertTrue(bible > unrelated);
    }

    @Test void cachedStoreDescriptionWidensSemanticContext() {
        float[] prepared = SemanticEmbeddingScorer.prepareQuery("scan a document", 384);
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

    @Test void barcodeIntentPrefersBarcodeCapabilityOverGenericCamera() {
        float[] prepared = SemanticEmbeddingScorer.prepareQuery("scan bar code", 384);
        Pojo opaqueScanner = pojo("Utility Pro");
        Pojo camera = pojo("Camera");

        float scannerScore = SemanticEmbeddingScorer.scorePrepared(
                prepared,
                opaqueScanner,
                "Scan QR codes, barcodes, EAN and UPC product codes with the camera.");
        float cameraScore = SemanticEmbeddingScorer.scorePrepared(
                prepared,
                camera,
                "Take photos and videos with manual camera controls.");

        assertTrue(scannerScore > cameraScore);
        assertTrue(scannerScore > 0.34f);
    }

    @Test void highDimensionPresetsPreserveBarcodeCapabilitySignal() {
        Pojo scanner = pojo("Utility Pro");
        Pojo camera = pojo("Camera");
        String scannerDescription =
                "Scan QR codes, barcodes, EAN and UPC product codes with the camera.";
        String cameraDescription =
                "Take photos and videos with manual camera controls.";

        for (int dimensions : new int[]{256, 320, 384, 400}) {
            float[] prepared = SemanticEmbeddingScorer.prepareQuery("scan barcode", dimensions);
            float scannerScore = SemanticEmbeddingScorer.scorePrepared(
                    prepared, scanner, scannerDescription);
            float cameraScore = SemanticEmbeddingScorer.scorePrepared(
                    prepared, camera, cameraDescription);
            assertTrue(scannerScore > cameraScore,
                    "barcode-capable app should win at " + dimensions + "D");
        }
    }

    private static Pojo pojo(String name) {
        Pojo pojo = new Pojo("test://" + name) { };
        pojo.setName(name, false);
        return pojo;
    }
}
