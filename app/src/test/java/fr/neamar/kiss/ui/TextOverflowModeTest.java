package fr.neamar.kiss.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextOverflowModeTest {
    @Test
    void autoExpandAppliesToBothRequestedVerticalLayouts() {
        assertTrue(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "vertical"));
        assertTrue(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "vertical_cards"));
    }

    @Test
    void autoExpandDoesNotLeakIntoOtherHistoryRenderers() {
        assertFalse(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "wheel_3d"));
        assertFalse(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "horizontal_icons"));
        assertFalse(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "horizontal_cards"));
        assertFalse(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "horizontal_names"));
        assertFalse(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "square_u"));
    }

    @Test
    void unknownOrMissingModeFallsBackToExistingAutoScrollBehaviour() {
        assertEquals(TextOverflowMode.AUTO_SCROLL, TextOverflowMode.normalizeMode(null));
        assertEquals(TextOverflowMode.AUTO_SCROLL, TextOverflowMode.normalizeMode("unexpected"));
        assertFalse(TextOverflowMode.shouldExpand(null, "vertical"));
        assertFalse(TextOverflowMode.shouldExpand("unexpected", "vertical_cards"));
    }
}
