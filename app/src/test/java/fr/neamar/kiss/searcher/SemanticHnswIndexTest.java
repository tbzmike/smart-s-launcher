package fr.neamar.kiss.searcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import fr.neamar.kiss.pojo.Pojo;

class SemanticHnswIndexTest {
    @Test
    void hnswReturnsBankingForGenericMoneyIntent() {
        List<Pojo> records = Arrays.asList(
                pojo("Calculator"),
                pojo("Spotify Music"),
                pojo("FNB Banking"),
                pojo("JW Library"),
                pojo("Google Maps"));

        List<SemanticHnswIndex.Hit> hits = SemanticHnswIndex.buildAndSearchForTest(
                records, "send money", 384, 3, 48);

        assertFalse(hits.isEmpty());
        assertEquals("FNB Banking", hits.get(0).pojo.getName());
        assertTrue(hits.get(0).score > 0f);
    }

    @Test
    void hnswReturnsBibleAppForScriptureIntent() {
        List<Pojo> records = Arrays.asList(
                pojo("Calculator"),
                pojo("JW Library"),
                pojo("Spotify Music"),
                pojo("Chrome Browser"));

        List<SemanticHnswIndex.Hit> hits = SemanticHnswIndex.buildAndSearchForTest(
                records, "read scripture", 384, 2, 48);

        assertFalse(hits.isEmpty());
        assertEquals("JW Library", hits.get(0).pojo.getName());
    }

    @Test
    void hnswFindsOpaqueAppFromDownloadedBarcodeDescription() {
        Pojo scanner = pojo("Utility Pro");
        Pojo camera = pojo("Camera");
        List<Pojo> records = Arrays.asList(
                pojo("WhatsApp Eric"),
                pojo("WhatsApp London"),
                pojo("Calculator"),
                pojo("Spotify Music"),
                camera,
                scanner,
                pojo("Calendar"),
                pojo("Files"));

        Map<String, String> metadata = new HashMap<>();
        metadata.put(scanner.id,
                "Scan QR codes, barcodes, EAN, UPC and Data Matrix product codes.");
        metadata.put(camera.id,
                "Take photos and videos with manual camera controls.");

        List<SemanticHnswIndex.Hit> hits = SemanticHnswIndex.buildAndSearchForTest(
                records, metadata, "scan barcode", 384, 4, 48);

        assertFalse(hits.isEmpty());
        assertEquals("Utility Pro", hits.get(0).pojo.getName());
        assertTrue(hits.get(0).score > 0.34f);
    }

    @Test
    void adaptiveGraphIncreasesConnectivityForHighDimensionVectors() {
        assertEquals(14, SemanticHnswIndex.connectionsForDimensions(256));
        assertEquals(15, SemanticHnswIndex.connectionsForDimensions(320));
        assertEquals(16, SemanticHnswIndex.connectionsForDimensions(384));
        assertEquals(16, SemanticHnswIndex.connectionsForDimensions(400));

        assertEquals(72, SemanticHnswIndex.constructionEfForDimensions(256));
        assertEquals(76, SemanticHnswIndex.constructionEfForDimensions(320));
        assertEquals(80, SemanticHnswIndex.constructionEfForDimensions(384));
        assertEquals(80, SemanticHnswIndex.constructionEfForDimensions(400));
    }

    @Test
    void fourHundredDimensionsStillFindDownloadedBarcodeCapability() {
        Pojo scanner = pojo("Utility Pro");
        Pojo camera = pojo("Camera");
        List<Pojo> records = Arrays.asList(
                pojo("WhatsApp"), pojo("Calculator"), camera, scanner, pojo("Spotify"));

        Map<String, String> metadata = new HashMap<>();
        metadata.put(scanner.id,
                "Scan QR codes, barcodes, EAN, UPC and Data Matrix product codes.");
        metadata.put(camera.id,
                "Take photos and videos with manual camera controls.");

        List<SemanticHnswIndex.Hit> hits = SemanticHnswIndex.buildAndSearchForTest(
                records, metadata, "scan barcode", 400, 3, 80);

        assertFalse(hits.isEmpty());
        assertEquals("Utility Pro", hits.get(0).pojo.getName());
    }

    @Test
    void hnswRespectsRequestedResultLimit() {
        List<Pojo> records = Arrays.asList(
                pojo("Spotify Music"),
                pojo("YouTube Music"),
                pojo("Radio"),
                pojo("Calculator"));

        List<SemanticHnswIndex.Hit> hits = SemanticHnswIndex.buildAndSearchForTest(
                records, "music", 128, 2, 48);

        assertEquals(2, hits.size());
    }

    private static Pojo pojo(String name) {
        Pojo pojo = new Pojo("test://" + name) { };
        pojo.setName(name, false);
        return pojo;
    }
}
