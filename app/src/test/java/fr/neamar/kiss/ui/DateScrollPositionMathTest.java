package fr.neamar.kiss.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DateScrollPositionMathTest {
    @Test void dragStartAndEndAreBounded() {
        assertEquals(0, DateScrollPositionMath.position(-1f, 500));
        assertEquals(499, DateScrollPositionMath.position(1f, 500));
        assertEquals(499, DateScrollPositionMath.position(200f, 500));
        assertEquals(0, DateScrollPositionMath.position(Float.NaN, 500));
    }
    @Test void worksWithSingleItemAndEmptyHistory() {
        assertEquals(0, DateScrollPositionMath.position(1f, 0));
        assertEquals(0, DateScrollPositionMath.position(1f, 1));
        assertEquals(0f, DateScrollPositionMath.fraction(0, 0, 0));
    }
    @Test void scrollPositionUsesVisibleWindowRatherThanTotalItems() {
        assertEquals(1f, DateScrollPositionMath.fraction(90, 10, 100));
        assertEquals(.5f, DateScrollPositionMath.fraction(45, 10, 100));
        assertEquals(0f, DateScrollPositionMath.fraction(-3, 10, 100));
    }
}
