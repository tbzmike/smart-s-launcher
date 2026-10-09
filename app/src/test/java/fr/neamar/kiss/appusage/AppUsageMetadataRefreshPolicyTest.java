package fr.neamar.kiss.appusage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fr.neamar.kiss.db.AppSourceMetadataRecord;

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
    void staleMetadataOlderThanUpdatedPackageRetriesEvenAfterPackageStateWasRecorded() {
        AppUsageStore.PackageState previous = new AppUsageStore.PackageState(
                "com.example.app", "Example", false,
                10L, 300L, "com.android.vending", "Google Play", null);
        AppSourceMetadataRecord metadata = new AppSourceMetadataRecord();
        metadata.packageName = "com.example.app";
        metadata.description = "Old description";
        metadata.fetchedAt = 250L;

        assertTrue(AppUsageSync.shouldRetryMetadataForPackage(
                previous, 300L, metadata, 400L));
    }

    @Test
    void failedDescriptionRetriesAfterDailyCooldown() {
        AppUsageStore.PackageState previous = new AppUsageStore.PackageState(
                "com.example.app", "Example", false,
                10L, 300L, "com.android.vending", "Google Play", null);
        AppSourceMetadataRecord metadata = new AppSourceMetadataRecord();
        metadata.packageName = "com.example.app";
        metadata.description = "";
        metadata.fetchedAt = 1000L;

        long oneDayLater = 1000L + 24L * 60L * 60L * 1000L;
        assertTrue(AppUsageSync.shouldRetryMetadataForPackage(
                previous, 300L, metadata, oneDayLater));
    }

    @Test
    void recentFailedDescriptionDoesNotHammerCataloguesEverySync() {
        AppUsageStore.PackageState previous = new AppUsageStore.PackageState(
                "com.example.app", "Example", false,
                10L, 300L, "com.android.vending", "Google Play", null);
        AppSourceMetadataRecord metadata = new AppSourceMetadataRecord();
        metadata.packageName = "com.example.app";
        metadata.description = "";
        metadata.fetchedAt = 1000L;

        assertFalse(AppUsageSync.shouldRetryMetadataForPackage(
                previous, 300L, metadata, 2000L));
    }

    @Test
    void changedLastUpdateTimeQueuesMetadataRefresh() {
        AppUsageStore.PackageState previous = new AppUsageStore.PackageState(
                "com.example.app", "Example", false,
                10L, 200L, "com.android.vending", "Google Play", null);

        assertTrue(AppUsageSync.shouldRefreshMetadataAfterPackageScan(previous, 300L));
    }
}
