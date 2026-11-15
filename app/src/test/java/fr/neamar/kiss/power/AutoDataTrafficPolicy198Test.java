package fr.neamar.kiss.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoDataTrafficPolicy198Test {
    @Test void largeDownloadsAcrossAnyAppNetworkTrafficProtectDataConnection() {
        assertEquals(AutoDataTrafficPolicy.Traffic.ACTIVE,
                AutoDataTrafficPolicy.classify(10_000, 510_000, 60_000));
        assertEquals(AutoDataTrafficPolicy.Traffic.ACTIVE,
                AutoDataTrafficPolicy.classify(0, 256_000, 30_000));
        assertEquals(AutoDataTrafficPolicy.Traffic.QUIET,
                AutoDataTrafficPolicy.classify(10_000, 15_000, 60_000));
    }

    @Test void unavailableOrTooShortSamplesNeverAuthorizeDataOff() {
        assertEquals(AutoDataTrafficPolicy.Traffic.UNKNOWN,
                AutoDataTrafficPolicy.classify(-1, 210_000, 60_000));
        assertEquals(AutoDataTrafficPolicy.Traffic.UNKNOWN,
                AutoDataTrafficPolicy.classify(100, 50, 60_000));
        assertEquals(AutoDataTrafficPolicy.Traffic.UNKNOWN,
                AutoDataTrafficPolicy.classify(100, 101, 5_000));
    }

    @Test void requireTwoConsecutiveQuietIntervalsAfterDetectedTransfer() {
        assertTrue(AutoDataTrafficPolicy.needsAnotherQuietWindow(true, 0));
        assertTrue(AutoDataTrafficPolicy.needsAnotherQuietWindow(true, 1));
        assertFalse(AutoDataTrafficPolicy.needsAnotherQuietWindow(true, 2));
        assertFalse(AutoDataTrafficPolicy.needsAnotherQuietWindow(false, 0));
    }
}
