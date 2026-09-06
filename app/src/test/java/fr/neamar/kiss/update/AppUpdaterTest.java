package fr.neamar.kiss.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppUpdaterTest {
    @Test
    void comparesThreePartVersions() {
        assertTrue(AppUpdater.compareVersions("3.30.51", "3.30.50") > 0);
        assertTrue(AppUpdater.compareVersions("3.31.0", "3.30.99") > 0);
        assertTrue(AppUpdater.compareVersions("4.0.0", "3.99.99") > 0);
    }

    @Test
    void acceptsVPrefixAndMissingTrailingParts() {
        assertEquals(0, AppUpdater.compareVersions("v3.30.51", "3.30.51"));
        assertEquals(0, AppUpdater.compareVersions("3.30", "3.30.0"));
    }

    @Test
    void doesNotTreatOlderBuildAsUpdate() {
        assertTrue(AppUpdater.compareVersions("3.30.37", "3.30.51") < 0);
    }

    @Test
    void parsesVerifiedVersionedReleaseManifest() throws Exception {
        String json = "{\"version\":\"3.30.51\",\"versionCode\":479,"
                + "\"runId\":34060000000,\"runNumber\":1470,"
                + "\"sha\":\"0123456789abcdef\",\"variant\":\"debug\","
                + "\"apkName\":\"app-debug.apk\","
                + "\"apkUrl\":\"https://github.com/tbzmike/smart-s-launcher/releases/download/v3.30.51/app-debug.apk\"}";
        AppUpdater.BuildInfo info = AppUpdater.parseBuildInfo(json);
        assertEquals("3.30.51", info.version);
        assertEquals(1470L, info.runNumber);
        assertEquals("debug", info.variant);
        assertEquals("app-debug.apk", info.apkName);
    }

    @Test
    void rejectsReleaseAssetForDifferentVersion() {
        String json = "{\"version\":\"3.30.51\",\"runId\":1,\"runNumber\":2,"
                + "\"sha\":\"0123456789abcdef\",\"variant\":\"debug\","
                + "\"apkName\":\"app-debug.apk\","
                + "\"apkUrl\":\"https://github.com/tbzmike/smart-s-launcher/releases/download/v3.30.50/app-debug.apk\"}";
        assertThrows(Exception.class, () -> AppUpdater.parseBuildInfo(json));
    }

    @Test
    void rejectsManifestThatPointsOutsideGitHubReleases() {
        String json = "{\"version\":\"3.30.51\",\"runId\":1,\"runNumber\":2,"
                + "\"sha\":\"0123456789abcdef\",\"variant\":\"debug\","
                + "\"apkName\":\"app-debug.apk\",\"apkUrl\":\"https://example.com/app-debug.apk\"}";
        assertThrows(Exception.class, () -> AppUpdater.parseBuildInfo(json));
    }

    @Test
    void rejectsNonDebugGreenArtifact() {
        String json = "{\"version\":\"3.30.51\",\"runId\":1,\"runNumber\":2,"
                + "\"sha\":\"0123456789abcdef\",\"variant\":\"release\","
                + "\"apkName\":\"app-release.apk\","
                + "\"apkUrl\":\"https://github.com/tbzmike/smart-s-launcher/releases/download/v3.30.51/app-release.apk\"}";
        assertThrows(Exception.class, () -> AppUpdater.parseBuildInfo(json));
    }
}
