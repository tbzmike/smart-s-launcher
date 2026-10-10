package fr.neamar.kiss.appusage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppMetadataRefreshPolicyTest {
    @Test void duplicateInstallUpdateBroadcastsQueueOnlyOneRevision() {
        assertTrue(AppMetadataRefreshPolicy.shouldQueuePackageRevision(3000L, 0L, false));
        assertFalse(AppMetadataRefreshPolicy.shouldQueuePackageRevision(3000L, 3000L, true));
        assertFalse(AppMetadataRefreshPolicy.shouldQueuePackageRevision(3000L, 3000L, false));
        assertTrue(AppMetadataRefreshPolicy.shouldQueuePackageRevision(4000L, 3000L, true));
        assertFalse(AppMetadataRefreshPolicy.shouldQueuePackageRevision(0L, 0L, true));
    }

    @Test void completedOldFetchCannotRemoveANewerUpdate() {
        assertTrue(AppMetadataRefreshPolicy.canAcknowledge(12L, 12L));
        assertFalse(AppMetadataRefreshPolicy.canAcknowledge(13L, 12L));
        assertFalse(AppMetadataRefreshPolicy.canAcknowledge(0L, 0L));
    }

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
