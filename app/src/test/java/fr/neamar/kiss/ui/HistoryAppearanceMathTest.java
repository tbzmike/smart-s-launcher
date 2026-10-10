package fr.neamar.kiss.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class HistoryAppearanceMathTest {
    @Test void iconSliderUsesTrueBaseSizeWithoutCompounding() {
        assertEquals(236, HistoryAppearanceMath.scaledIconPx(100, 236));
        assertEquals(100, HistoryAppearanceMath.scaledIconPx(100, 100));
        assertEquals(236, HistoryAppearanceMath.scaledIconPx(100, 236));
        assertEquals(50, HistoryAppearanceMath.scaledIconPx(100, 50));
    }
    @Test void independentTextSizeRemainsEffectiveWithGlobalScaling() {
        assertEquals(21f, HistoryAppearanceMath.scaledTextSp(21, 100), .001f);
        assertEquals(25.2f, HistoryAppearanceMath.scaledTextSp(21, 120), .001f);
        assertEquals(10.8f, HistoryAppearanceMath.scaledTextSp(9, 120), .001f);
        assertEquals(12f, HistoryAppearanceMath.scaledTextSp(12, 100), .001f);
    }
    @Test void recycledRowAlwaysGetsFreshStyleSignatureForDifferentItems() {
        int app1 = HistoryAppearanceMath.rowStyleSignature(43, 236, 112L);
        int app2 = HistoryAppearanceMath.rowStyleSignature(43, 236, 113L);
        org.junit.jupiter.api.Assertions.assertNotEquals(app1, app2);
        assertEquals(app1, HistoryAppearanceMath.rowStyleSignature(43, 236, 112L));
    }
    @Test void scalingIsClampedAndNeverBecomesZero() {
        assertEquals(16f, HistoryAppearanceMath.scaledTextSp(10, 999), .001f);
        assertEquals(7f, HistoryAppearanceMath.scaledTextSp(10, 0), .001f);
        assertEquals(1, HistoryAppearanceMath.scaledIconPx(0, 100));
    }
}
