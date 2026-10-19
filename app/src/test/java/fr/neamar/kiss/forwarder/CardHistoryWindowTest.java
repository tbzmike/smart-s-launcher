package fr.neamar.kiss.forwarder;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CardHistoryWindowTest {
    @Test void neverAttachesMoreThan24ComplexCards() {
        CardHistoryWindow window = new CardHistoryWindow();
        assertEquals(24, window.size(10000));
        assertEquals(9976, window.start(10000));
        assertEquals(10000, window.end(10000));
        assertTrue(window.hasOlder(10000));
        assertFalse(window.hasNewer(10000));
    }

    @Test void everyOlderAndNewerPageIsReachableInOrder() {
        CardHistoryWindow window = new CardHistoryWindow();
        int total = 550;
        int iterations = 0;
        while (window.hasOlder(total)) {
            int oldStart = window.start(total);
            window.older(total);
            assertTrue(window.start(total) < oldStart, "Must progress towards older History");
            assertTrue(window.size(total) <= 24);
            assertTrue(++iterations <= 40);
        }
        assertEquals(0, window.start(total));
        while (window.hasNewer(total)) {
            int lastEnd = window.end(total);
            window.newer(total);
            assertTrue(window.end(total) > lastEnd);
            assertTrue(window.size(total) <= 24);
            assertTrue(++iterations <= 80);
        }
        assertEquals(total, window.end(total));
    }

    @Test void newLaunchFollowsNewestWithoutLosingOlderPages() {
        CardHistoryWindow window = new CardHistoryWindow();
        assertEquals(100, window.end(100));
        assertEquals(101, window.end(101));
        window.older(101);
        int oldPage = window.end(101);
        assertEquals(oldPage, window.end(103));
        assertTrue(window.hasNewer(103));
        window.reset();
        assertEquals(103, window.end(103));
    }

    @Test void handlesEmptyAndSmallHistory() {
        CardHistoryWindow window = new CardHistoryWindow();
        for (int total = 0; total < 24; total++) {
            window.reset();
            assertEquals(0, window.start(total));
            assertEquals(total, window.end(total));
            assertFalse(window.hasOlder(total));
        }
    }
}
