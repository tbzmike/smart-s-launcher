package fr.neamar.kiss.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppUpdaterTest {
    @Test
    void comparesThreePartVersions() {
        assertTrue(AppUpdater.compareVersions("3.30.44", "3.30.43") > 0);
        assertTrue(AppUpdater.compareVersions("3.31.0", "3.30.99") > 0);
        assertTrue(AppUpdater.compareVersions("4.0.0", "3.99.99") > 0);
    }

    @Test
    void acceptsGitHubVPrefixAndMissingTrailingParts() {
        assertEquals(0, AppUpdater.compareVersions("v3.30.44", "3.30.44"));
        assertEquals(0, AppUpdater.compareVersions("3.30", "3.30.0"));
    }

    @Test
    void doesNotTreatOlderReleaseAsUpdate() {
        assertTrue(AppUpdater.compareVersions("3.30.37", "3.30.44") < 0);
    }
}
