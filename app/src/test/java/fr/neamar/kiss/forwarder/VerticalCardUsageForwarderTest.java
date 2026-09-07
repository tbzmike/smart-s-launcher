package fr.neamar.kiss.forwarder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class VerticalCardUsageForwarderTest {
    @Test
    public void formatsUsageDurationsWithoutDroppingMinutes() {
        assertEquals("0m", VerticalCardUsageForwarder.formatDuration(0L));
        assertEquals("<1m", VerticalCardUsageForwarder.formatDuration(30_000L));
        assertEquals("49m", VerticalCardUsageForwarder.formatDuration(49L * 60_000L));
        assertEquals("1h 31m", VerticalCardUsageForwarder.formatDuration(91L * 60_000L));
    }

    @Test
    public void formatsHumanLaunchCounts() {
        assertEquals("Launched 0 times", VerticalCardUsageForwarder.formatLaunchCount(0));
        assertEquals("Launched once", VerticalCardUsageForwarder.formatLaunchCount(1));
        assertEquals("Launched twice", VerticalCardUsageForwarder.formatLaunchCount(2));
        assertEquals("Launched 7 times", VerticalCardUsageForwarder.formatLaunchCount(7));
    }
}
