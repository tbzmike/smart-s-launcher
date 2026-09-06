package fr.neamar.kiss.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppUpdaterTest {
    @Test
    void comparesThreePartVersions() {
        assertTrue(AppUpdater.compareVersions("3.30.47", "3.30.46") > 0);
        assertTrue(AppUpdater.compareVersions("3.31.0", "3.30.99") > 0);
        assertTrue(AppUpdater.compareVersions("4.0.0", "3.99.99") > 0);
    }

    @Test
    void acceptsVPrefixAndMissingTrailingParts() {
        assertEquals(0, AppUpdater.compareVersions("v3.30.47", "3.30.47"));
        assertEquals(0, AppUpdater.compareVersions("3.30", "3.30.0"));
    }

    @Test
    void doesNotTreatOlderBuildAsUpdate() {
        assertTrue(AppUpdater.compareVersions("3.30.37", "3.30.47") < 0);
    }

    @Test
    void parsesVerifiedLatestGreenCdnManifest() throws Exception {
        String json = "{\"version\":\"3.30.48\",\"versionCode\":476,"
                + "\"runId\":34060000000,\"runNumber\":1444,"
                + "\"sha\":\"0123456789abcdef\",\"variant\":\"debug\","
                + "\"apkName\":\"app-debug.apk\","
                + "\"apkUrl\":\"https://cdn.jsdelivr.net/gh/tbzmike/smart-s-launcher@updater-channel/app-debug.apk?run=1444\"}";
        AppUpdater.BuildInfo info = AppUpdater.parseBuildInfo(json);
        assertEquals("3.30.48", info.version);
        assertEquals(1444L, info.runNumber);
        assertEquals("debug", info.variant);
        assertEquals("app-debug.apk", info.apkName);
    }

    @Test
    void stillAcceptsVerifiedGitHubFallbackAsset() throws Exception {
        String json = "{\"version\":\"3.30.48\",\"runId\":1,\"runNumber\":2,"
                + "\"sha\":\"0123456789abcdef\",\"variant\":\"debug\","
                + "\"apkName\":\"app-debug.apk\","
                + "\"apkUrl\":\"https://github.com/tbzmike/smart-s-launcher/releases/download/latest-green/app-debug.apk\"}";
        assertEquals("3.30.48", AppUpdater.parseBuildInfo(json).version);
    }

    @Test
    void rejectsManifestThatPointsOutsideUpdaterChannel() {
        String json = "{\"version\":\"3.30.48\",\"runId\":1,\"runNumber\":2,"
                + "\"sha\":\"0123456789abcdef\",\"variant\":\"debug\","
                + "\"apkName\":\"app-debug.apk\",\"apkUrl\":\"https://example.com/app-debug.apk\"}";
        assertThrows(Exception.class, () -> AppUpdater.parseBuildInfo(json));
    }

    @Test
    void rejectsJsDelivrPathOutsideDedicatedChannel() {
        String json = "{\"version\":\"3.30.48\",\"runId\":1,\"runNumber\":2,"
                + "\"sha\":\"0123456789abcdef\",\"variant\":\"debug\","
                + "\"apkName\":\"app-debug.apk\","
                + "\"apkUrl\":\"https://cdn.jsdelivr.net/gh/tbzmike/smart-s-launcher@master/app-debug.apk\"}";
        assertThrows(Exception.class, () -> AppUpdater.parseBuildInfo(json));
    }

    @Test
    void rejectsNonDebugGreenArtifact() {
        String json = "{\"version\":\"3.30.48\",\"runId\":1,\"runNumber\":2,"
                + "\"sha\":\"0123456789abcdef\",\"variant\":\"release\","
                + "\"apkName\":\"app-release.apk\","
                + "\"apkUrl\":\"https://cdn.jsdelivr.net/gh/tbzmike/smart-s-launcher@updater-channel/app-release.apk?run=2\"}";
        assertThrows(Exception.class, () -> AppUpdater.parseBuildInfo(json));
    }
}
