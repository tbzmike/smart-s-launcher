package fr.neamar.kiss.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoDataPolicyTest {
    @Test void delayIsAlwaysBetweenOneAndThirtyMinutes() {
        assertEquals(1, AutoDataPolicy.delayMinutes(-10));
        assertEquals(1, AutoDataPolicy.delayMinutes(0));
        assertEquals(5, AutoDataPolicy.delayMinutes(5));
        assertEquals(30, AutoDataPolicy.delayMinutes(30));
        assertEquals(30, AutoDataPolicy.delayMinutes(50));
    }

    @Test void screenOffArmsOnlyWhenEnabledAndUnplugged() {
        assertTrue(AutoDataPolicy.shouldSchedule(true, false, false));
        assertFalse(AutoDataPolicy.shouldSchedule(false, false, false));
        assertFalse(AutoDataPolicy.shouldSchedule(true, true, false));
        assertFalse(AutoDataPolicy.shouldSchedule(true, false, true));
    }

    @Test void neverDisableManuallyOffDataOrDuplicateACompletedDisable() {
        assertTrue(AutoDataPolicy.shouldDisable(true, false, false, true, false));
        assertFalse(AutoDataPolicy.shouldDisable(true, false, false, false, false));
        assertFalse(AutoDataPolicy.shouldDisable(true, false, false, true, true));
    }

    @Test void screenOnPluggingInAndDisablingFeatureRequireRestoreOnlyIfOwned() {
        assertTrue(AutoDataPolicy.shouldRestore(true, true, true, false));
        assertTrue(AutoDataPolicy.shouldRestore(true, true, false, true));
        assertTrue(AutoDataPolicy.shouldRestore(true, false, false, false));
        assertFalse(AutoDataPolicy.shouldRestore(false, true, true, false));
        assertFalse(AutoDataPolicy.shouldRestore(true, true, false, false));
    }
}
