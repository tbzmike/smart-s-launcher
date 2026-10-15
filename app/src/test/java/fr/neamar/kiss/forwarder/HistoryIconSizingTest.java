package fr.neamar.kiss.forwarder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryIconSizingTest {
    @Test void eachProfileAndShortcutSliderMapsToARealVisibleIconSize() {
        int[] settings = {50, 81, 100, 163, 181, 206, 210, 240};
        for (int value : settings) {
            int effective = HistoryIconSizing.combinedPercent(value, 100);
            assertEquals(value, effective);
            assertEquals(Math.max(32, Math.round(66f * value / 100)),
                    HistoryIconSizing.iconDp(66, effective));
        }
        assertTrue(HistoryIconSizing.iconDp(66, 210)
                > HistoryIconSizing.iconDp(66, 100));
    }

    @Test void cardModifierIsAppliedOnceAndBoundsAreHonored() {
        assertEquals(210, HistoryIconSizing.combinedPercent(210, 100));
        assertEquals(126, HistoryIconSizing.combinedPercent(210, 60));
        assertEquals(240, HistoryIconSizing.combinedPercent(210, 180));
        assertEquals(50, HistoryIconSizing.combinedPercent(50, 60));
    }
}
