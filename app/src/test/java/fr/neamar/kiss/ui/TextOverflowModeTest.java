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
    }

    @Test
    void unknownOrMissingModeFallsBackToExistingAutoScrollBehaviour() {
        assertEquals(TextOverflowMode.AUTO_SCROLL, TextOverflowMode.normalizeMode(null));
        assertEquals(TextOverflowMode.AUTO_SCROLL, TextOverflowMode.normalizeMode("unexpected"));
        assertFalse(TextOverflowMode.shouldExpand(null, "vertical"));
        assertFalse(TextOverflowMode.shouldExpand("unexpected", "vertical_cards"));
    }

    @Test
    void collapsedPreviewUsesApproximatelyHalfOfWrappedLines() {
        assertEquals(0, TextOverflowMode.collapsedPreviewLineCount(0));
        assertEquals(1, TextOverflowMode.collapsedPreviewLineCount(1));
        assertEquals(1, TextOverflowMode.collapsedPreviewLineCount(2));
        assertEquals(2, TextOverflowMode.collapsedPreviewLineCount(3));
        assertEquals(2, TextOverflowMode.collapsedPreviewLineCount(4));
        assertEquals(3, TextOverflowMode.collapsedPreviewLineCount(5));
    }
}
