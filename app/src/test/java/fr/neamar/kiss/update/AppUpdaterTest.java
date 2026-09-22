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
    void markVaultStyleDiscoveryDoesNotUseGitHubApiAsPrimary() {
        assertTrue(AppUpdater.PRIMARY_MANIFEST_URL.startsWith("https://github.com/"));
        assertTrue(AppUpdater.MIRROR_MANIFEST_URL.startsWith("https://raw.githubusercontent.com/"));
        assertTrue(AppUpdater.RELEASE_API.startsWith("https://api.github.com/"));
    }

    @Test
    void selectsReleaseAssetThatMatchesBuildSigningChannel() {
        assertEquals("SmartSLauncher-debug.apk",
                AppUpdater.expectedReleaseAssetName("3.30.115", true));
        assertEquals("SmartSLauncher.apk",
                AppUpdater.expectedReleaseAssetName("v3.30.115", false));
        assertEquals(
                "https://github.com/tbzmike/smart-s-launcher/releases/latest/download/SmartSLauncher-debug.apk",
                AppUpdater.latestStableAssetUrl(true));
        assertEquals(
                "https://github.com/tbzmike/smart-s-launcher/releases/latest/download/SmartSLauncher.apk",
                AppUpdater.latestStableAssetUrl(false));
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
