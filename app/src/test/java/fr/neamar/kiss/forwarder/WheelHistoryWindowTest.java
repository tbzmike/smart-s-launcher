package fr.neamar.kiss.forwarder;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WheelHistoryWindowTest {
    @Test void wheelNeverMaterializesMoreThanTwelveRows() {
        WheelHistoryWindow window = new WheelHistoryWindow();
        assertEquals(2488, window.start(2500));
        assertEquals(2500, window.end(2500));
        assertTrue(window.hasOlder(2500));
        assertFalse(window.hasNewer(2500));
    }

    @Test void olderAndNewerPagesRemainNavigable() {
        WheelHistoryWindow window = new WheelHistoryWindow();
        int total = 300;
        int steps = 0;
        while (window.hasOlder(total)) {
            int lastStart = window.start(total);
            window.older(total);
            assertTrue(window.start(total) < lastStart);
            assertTrue(window.end(total) - window.start(total) <= 12);
            assertTrue(++steps < 100);
        }
        assertEquals(0, window.start(total));
        while (window.hasNewer(total)) {
            int lastEnd = window.end(total);
            window.newer(total);
            assertTrue(window.end(total) > lastEnd);
            assertTrue(window.end(total) - window.start(total) <= 12);
            assertTrue(++steps < 200);
        }
        assertEquals(total, window.end(total));
    }

    @Test void newResultFollowsNewestWhileOlderPagesRemainStable() {
        WheelHistoryWindow window = new WheelHistoryWindow();
        assertEquals(40, window.end(40));
        assertEquals(41, window.end(41));
        window.older(41);
        int before = window.end(41);
        assertEquals(before, window.end(44));
        assertTrue(window.hasNewer(44));
        window.reset();
        assertEquals(44, window.end(44));
    }
}
