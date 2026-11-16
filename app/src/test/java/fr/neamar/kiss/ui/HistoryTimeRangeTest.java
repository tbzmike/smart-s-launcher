package fr.neamar.kiss.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;
import java.util.TimeZone;

class HistoryTimeRangeTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static long at(String timestamp) {
        return Instant.parse(timestamp).toEpochMilli();
    }

    @Test void visibleRangeUsesActualEventTimestampsNotClockOrRowOrder() {
        HistoryTimeRange range = new HistoryTimeRange();
        range.include(at("2026-11-16T15:50:59Z"));
        range.include(at("2026-11-16T15:33:00Z"));
        range.include(at("2026-11-16T15:43:32Z"));
        assertEquals("15h33 – 15h50",
                range.format(HistoryTimeRange.FORMAT_24H_H, Locale.US, UTC));
        assertEquals("15:33 – 15:50",
                range.format(HistoryTimeRange.FORMAT_24H_COLON, Locale.US, UTC));
        assertEquals("3:33 PM – 3:50 PM",
                range.format(HistoryTimeRange.FORMAT_12H, Locale.US, UTC));
    }

    @Test void singleEventDisplaysOneTimeAndInvalidLegacyTimesAreNotGuessed() {
        HistoryTimeRange range = new HistoryTimeRange();
        range.include(0);
        range.include(-50);
        assertFalse(range.hasTime());
        assertEquals("", range.format(HistoryTimeRange.FORMAT_24H_H, Locale.US, UTC));
        range.include(at("2026-11-16T02:39:26Z"));
        assertTrue(range.hasTime());
        assertEquals("02h39", range.format(HistoryTimeRange.FORMAT_24H_H, Locale.US, UTC));
        assertEquals("2:39 AM", range.format(HistoryTimeRange.FORMAT_12H, Locale.US, UTC));
        range.include(at("2026-11-16T02:39:59Z"));
        assertEquals("02:39", range.format(HistoryTimeRange.FORMAT_24H_COLON, Locale.US, UTC));
    }

    @Test void unknownFormatFallsBackToRequested15h33Style() {
        HistoryTimeRange range = new HistoryTimeRange();
        range.include(at("2026-11-16T15:33:00Z"));
        assertEquals("15h33", range.format("invalid", Locale.US, UTC));
    }
}
