package fr.neamar.kiss.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuiltInKeyboardSizingTest {
    @Test
    void heightIsHardCappedAtHalfOfAvailableScreen() {
        assertEquals(1000, BuiltInKeyboardSizing.heightPx(2000, 50));
        assertEquals(1000, BuiltInKeyboardSizing.heightPx(2000, 99));
        assertTrue(BuiltInKeyboardSizing.heightPx(2000, 45) <= 1000);
    }

    @Test
    void dimensionsClampToSupportedIndependentRanges() {
        assertEquals(20, BuiltInKeyboardSizing.clampHeightPercent(-1));
        assertEquals(50, BuiltInKeyboardSizing.clampHeightPercent(90));
        assertEquals(45, BuiltInKeyboardSizing.clampWidthPercent(10));
        assertEquals(100, BuiltInKeyboardSizing.clampWidthPercent(140));
        assertEquals(55, BuiltInKeyboardSizing.clampButtonPercent(0));
        assertEquals(100, BuiltInKeyboardSizing.clampButtonPercent(120));
        assertEquals(10, BuiltInKeyboardSizing.clampLabelSizeSp(2));
        assertEquals(36, BuiltInKeyboardSizing.clampLabelSizeSp(80));
    }

    @Test
    void widthAndButtonSizingDoNotChangeKeyboardHeight() {
        int height = BuiltInKeyboardSizing.heightPx(2400, 40);
        assertEquals(960, height);
        assertEquals(540, BuiltInKeyboardSizing.widthPx(1080, 50));
        assertEquals(0, BuiltInKeyboardSizing.extraButtonInsetDp(100));
        assertEquals(9, BuiltInKeyboardSizing.extraButtonInsetDp(55));
        assertEquals(960, BuiltInKeyboardSizing.heightPx(2400, 40));
    }
}
