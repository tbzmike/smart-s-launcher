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
        assertTrue(AppUpdater.compareVersions("3.30.117", "3.30.116") > 0);
        assertTrue(AppUpdater.compareVersions("3.31.0", "3.30.999") > 0);
        assertTrue(AppUpdater.compareVersions("4.0.0", "3.99.99") > 0);
    }

    @Test
    void acceptsVPrefixAndMissingTrailingParts() {
        assertEquals(0, AppUpdater.compareVersions("v3.30.117", "3.30.117"));
        assertEquals(0, AppUpdater.compareVersions("3.30", "3.30.0"));
        assertEquals("3.30.117", AppUpdater.normalizeVersion("V3.30.117"));
    }

    @Test
    void usesTheSameLatestReleaseApiPatternAsMarkVault() {
        assertEquals(
                "https://api.github.com/repos/tbzmike/smart-s-launcher/releases/latest",
                AppUpdater.RELEASE_API);
    }

    @Test
    void selectsFixedReleaseAssetForInstalledSigningChannel() {
        assertEquals("SmartSLauncher-debug.apk",
                AppUpdater.expectedReleaseAssetName(true));
        assertEquals("SmartSLauncher.apk",
                AppUpdater.expectedReleaseAssetName(false));
    }

    @Test
    void computesDeterministicSha256(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("release.apk");
        Files.write(file, "Smart S Launcher".getBytes(StandardCharsets.UTF_8));
        assertEquals(
                "7db324f820acd9a9b2c2bdaeff8aa9a333b6f62e84e41b829f9233b353009075",
                AppUpdater.sha256Of(file.toFile()));
    }
}
