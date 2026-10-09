package fr.neamar.kiss.appusage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppUsageMetadataRefreshPolicyTest {
    @Test
    void firstCatalogueScanDoesNotPretendEveryExistingAppWasJustUpdated() {
        assertFalse(AppUsageSync.shouldRefreshMetadataAfterPackageScan(null, 123456L));
    }

    @Test
    void unchangedLastUpdateTimeDoesNotQueueMetadataRefresh() {
        AppUsageStore.PackageState previous = new AppUsageStore.PackageState(
                "com.example.app", "Example", false,
                10L, 200L, "com.android.vending", "Google Play", null);

        assertFalse(AppUsageSync.shouldRefreshMetadataAfterPackageScan(previous, 200L));
    }

    @Test
    void changedLastUpdateTimeQueuesMetadataRefresh() {
        AppUsageStore.PackageState previous = new AppUsageStore.PackageState(
                "com.example.app", "Example", false,
                10L, 200L, "com.android.vending", "Google Play", null);

        assertTrue(AppUsageSync.shouldRefreshMetadataAfterPackageScan(previous, 300L));
    }
}
