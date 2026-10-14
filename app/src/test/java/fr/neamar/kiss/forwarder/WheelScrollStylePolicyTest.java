package fr.neamar.kiss.forwarder;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.Arrays;

class WheelScrollStylePolicyTest {
    @Test void allSupportedScrollStylesHaveSafeAndDistinctWheelGeometry() {
        String[] styles = {"none", "classic", "focus", "depth", "wave", "slide", "stack",
                "zoom", "tilt", "cascade", "flip", "helix", "fan", "bounce",
                "parallax", "swing", "accordion", "spiral", "cube"};
        WheelScrollStylePolicy.Profile classic = WheelScrollStylePolicy.forStyle("classic");
        for (String style : styles) {
            WheelScrollStylePolicy.Profile motion = WheelScrollStylePolicy.forStyle(style);
            assertTrue(motion.tilt > 0f && motion.tilt <= 105f);
            assertTrue(motion.depth >= 0f && motion.depth <= .35f);
            assertTrue(motion.fade >= 0f && motion.fade <= .8f);
        }
        assertNotEquals(classic.lift, WheelScrollStylePolicy.forStyle("bounce").lift);
        assertNotEquals(classic.yaw, WheelScrollStylePolicy.forStyle("cube").yaw);
        assertNotEquals(classic.sideways, WheelScrollStylePolicy.forStyle("wave").sideways);
    }
    @Test void unknownStylesFallBackWithoutBreakingThreeDimensionalWheel() {
        WheelScrollStylePolicy.Profile classic = WheelScrollStylePolicy.forStyle("classic");
        assertEquals(classic.tilt, WheelScrollStylePolicy.forStyle("unknown").tilt);
        assertEquals(classic.depth, WheelScrollStylePolicy.forStyle(null).depth);
    }
}
