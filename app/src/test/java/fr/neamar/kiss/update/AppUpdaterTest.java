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
    void parsesMatchingDebugAndProductionReleaseAssets() throws Exception {
        String sha = "a".repeat(64);
        String json = "{\"draft\":false,\"prerelease\":false,\"assets\":["
                + "{\"name\":\"SmartSLauncher.apk\",\"state\":\"uploaded\",\"size\":2724408,"
                + "\"digest\":\"sha256:" + sha + "\","
                + "\"browser_download_url\":\"https://github.com/tbzmike/smart-s-launcher/releases/download/v3.30.171/SmartSLauncher.apk\"},"
                + "{\"name\":\"SmartSLauncher-debug.apk\",\"state\":\"uploaded\",\"size\":3780658,"
                + "\"digest\":\"sha256:" + sha + "\","
                + "\"browser_download_url\":\"https://github.com/tbzmike/smart-s-launcher/releases/download/v3.30.171/SmartSLauncher-debug.apk\"}]}";
        assertEquals("SmartSLauncher-debug.apk",
                AppUpdater.parseGitHubLatestRelease(json, true).name);
        assertEquals(3780658L, AppUpdater.parseGitHubLatestRelease(json, true).size);
        assertEquals("SmartSLauncher.apk",
                AppUpdater.parseGitHubLatestRelease(json, false).name);
    }

    @Test
    void parsesBackupManifestForBothSigningChannels() throws Exception {
        String sha = "b".repeat(64);
        String base = "https://github.com/tbzmike/smart-s-launcher/releases/download/v3.30.171/";
        String json = "{\"version\":\"3.30.171\","
                + "\"production\":{\"apkName\":\"SmartSLauncher.apk\",\"apkUrl\":\"" + base + "SmartSLauncher.apk\","
                + "\"size\":2700000,\"sha256\":\"" + sha + "\"},"
                + "\"debug\":{\"apkName\":\"SmartSLauncher-debug.apk\",\"apkUrl\":\"" + base + "SmartSLauncher-debug.apk\","
                + "\"size\":3800000,\"sha256\":\"" + sha + "\"}}";
        assertEquals("SmartSLauncher.apk", AppUpdater.parseChannelManifest(json, false).name);
        assertEquals("SmartSLauncher-debug.apk", AppUpdater.parseChannelManifest(json, true).name);
        assertEquals(3800000L, AppUpdater.parseChannelManifest(json, true).size);
    }

    @Test
    void rejectsWrongSigningChannelAndStaleManifestUrls() {
        String sha = "c".repeat(64);
        String json = "{\"version\":\"3.30.171\",\"debug\":"
                + "{\"apkName\":\"SmartSLauncher.apk\",\"apkUrl\":"
                + "\"https://github.com/tbzmike/smart-s-launcher/releases/download/v3.30.170/SmartSLauncher.apk\","
                + "\"size\":3450000,\"sha256\":\"" + sha + "\"}}";
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> AppUpdater.parseChannelManifest(json, true));
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
