package fr.neamar.kiss.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppUpdaterTest {
    @Test
    void comparesThreePartVersions() {
        assertTrue(AppUpdater.compareVersions("3.30.111", "3.30.110") > 0);
        assertTrue(AppUpdater.compareVersions("3.31.0", "3.30.999") > 0);
        assertTrue(AppUpdater.compareVersions("4.0.0", "3.99.99") > 0);
    }

    @Test
    void acceptsVPrefixAndMissingTrailingParts() {
        assertEquals(0, AppUpdater.compareVersions("v3.30.111", "3.30.111"));
        assertEquals(0, AppUpdater.compareVersions("3.30", "3.30.0"));
        assertEquals("3.30.111", AppUpdater.normalizeVersion("V3.30.111"));
    }

    @Test
    void olderBuildIsNotAnUpdate() {
        assertTrue(AppUpdater.compareVersions("3.30.110", "3.30.111") < 0);
    }

    @Test
    void computesDeterministicSha256(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("release.apk");
        Files.write(file, "Smart S Launcher".getBytes(StandardCharsets.UTF_8));
        assertEquals(
                "a0c477d97b9e1923d00f5b76f53f5fd38ce6c972a5e4cb8b21fcb9e56130501d",
                AppUpdater.sha256Of(file.toFile()));
    }
}
