package fr.neamar.kiss.appusage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppMetadataRefreshPolicyTest {
    @Test
    void queuesOnlyWhenAPreviouslyKnownPackageHasActuallyUpdated() {
        long install = 1_000_000L;
        long oldUpdate = 2_000_000L;
        long newUpdate = 3_000_000L;

        assertTrue(AppMetadataRefreshPolicy.shouldQueueFromReconciliation(
                oldUpdate, newUpdate, install));
        assertFalse(AppMetadataRefreshPolicy.shouldQueueFromReconciliation(
                oldUpdate, oldUpdate, install));
        assertFalse(AppMetadataRefreshPolicy.shouldQueueFromReconciliation(
                0L, newUpdate, install));
        assertFalse(AppMetadataRefreshPolicy.shouldQueueFromReconciliation(
                oldUpdate, oldUpdate - 1L, install));
    }
}
