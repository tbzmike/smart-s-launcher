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
                records, "send money", 384, 3, 64);

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
                records, "read scripture", 384, 2, 64);

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
                records, metadata, "scan barcode", 384, 4, 64);

        assertFalse(hits.isEmpty());
        assertEquals("Utility Pro", hits.get(0).pojo.getName());
        assertTrue(hits.get(0).score > 0.34f);
    }

    @Test
    void hnswRespectsRequestedResultLimit() {
        List<Pojo> records = Arrays.asList(
                pojo("Spotify Music"),
                pojo("YouTube Music"),
                pojo("Radio"),
                pojo("Calculator"));

        List<SemanticHnswIndex.Hit> hits = SemanticHnswIndex.buildAndSearchForTest(
                records, "music", 384, 2, 64);

        assertEquals(2, hits.size());
    }

    @Test
    void highDimensionProfilesUseCompactExactDivisorNavigation() {
        assertEquals(128, SemanticHnswIndex.navigationDimensionsForTest(256));
        assertEquals(128, SemanticHnswIndex.navigationDimensionsForTest(384));
        assertEquals(100, SemanticHnswIndex.navigationDimensionsForTest(400));
    }

    private static Pojo pojo(String name) {
        Pojo pojo = new Pojo("test://" + name) { };
        pojo.setName(name, false);
        return pojo;
    }
}
