package fr.neamar.kiss.forwarder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartCardShortcutTapPolicyTest {

    @Test
    void shortcutMessageNeverExpandsInsteadOfLaunching() {
        assertFalse(SmartCardListForwarder.shouldExpandMessageBeforeLaunch(
                false, true, true, false, true));
    }

    @Test
    void ordinaryCardCanStillExpandLongPreviewBeforeLaunch() {
        assertTrue(SmartCardListForwarder.shouldExpandMessageBeforeLaunch(
                false, false, true, false, true));
    }

    @Test
    void notificationCardNeverUsesGenericExpandBeforeLaunch() {
        assertFalse(SmartCardListForwarder.shouldExpandMessageBeforeLaunch(
                true, false, true, false, true));
    }

    @Test
    void expandedOrShortMessageDoesNotExpandAgain() {
        assertFalse(SmartCardListForwarder.shouldExpandMessageBeforeLaunch(
                false, false, true, true, true));
        assertFalse(SmartCardListForwarder.shouldExpandMessageBeforeLaunch(
                false, false, true, false, false));
    }
}
