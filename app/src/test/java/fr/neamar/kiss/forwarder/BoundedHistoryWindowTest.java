package fr.neamar.kiss.forwarder;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class BoundedHistoryWindowTest {
    @Test public void retainsAtMost64RowsEvenWithHugeHistory() {
        BoundedHistoryWindow page = new BoundedHistoryWindow();
        assertEquals(10000, page.end(10000));
        assertEquals(9936, page.start(10000));
        assertEquals(64, page.size(10000));
        assertTrue(page.hasOlder(10000));
        assertFalse(page.hasNewer(10000));
    }

    @Test public void everyPageCanBeNavigatedWithoutDroppingAdapterResults() {
        BoundedHistoryWindow page = new BoundedHistoryWindow();
        int total = 550;
        int pages = 0;
        while (page.hasOlder(total)) {
            int previousStart = page.start(total);
            page.older(total);
            assertTrue(page.start(total) < previousStart);
            assertTrue(page.size(total) <= 64);
            if (++pages > 20) fail("Older page progress stuck");
        }
        assertEquals(0, page.start(total));
        while (page.hasNewer(total)) {
            int previousEnd = page.end(total);
            page.newer(total);
            assertTrue(page.end(total) > previousEnd);
            assertTrue(page.size(total) <= 64);
            if (++pages > 40) fail("Newer page progress stuck");
        }
        assertEquals(total, page.end(total));
    }

    @Test public void newlyLaunchedItemFollowsNewestPageWithoutOverwritingOlderPage() {
        BoundedHistoryWindow page = new BoundedHistoryWindow();
        assertEquals(100, page.end(100));
        assertEquals(101, page.end(101));
        page.older(101);
        int oldEnd = page.end(101);
        assertEquals(oldEnd, page.end(103));
        assertTrue(page.hasNewer(103));
        page.reset();
        assertEquals(103, page.end(103));
    }

    @Test public void zeroToSmallHistoryKeepsAllEntries() {
        BoundedHistoryWindow page = new BoundedHistoryWindow();
        for (int n = 0; n < 64; n++) {
            page.reset();
            assertEquals(0, page.start(n));
            assertEquals(n, page.end(n));
            assertFalse(page.hasOlder(n));
            assertFalse(page.hasNewer(n));
        }
    }
}
