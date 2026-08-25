package fr.neamar.kiss.battery;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class BatteryHistoryStoreTest {
    private static final long MINUTE = 60_000L;

    @Test
    void learnsCapacityFromDischargingRange() {
        long estimate = BatteryHistoryStore.estimateFullCapacityUah(Arrays.asList(
                point(0L, 80, false, 3_200_000L),
                point(3L * MINUTE, 78, false, 3_120_000L),
                point(8L * MINUTE, 75, false, 3_000_000L)));

        assertThat(estimate, is(4_000_000L));
    }

    @Test
    void learnsCapacityFromChargingRange() {
        long estimate = BatteryHistoryStore.estimateFullCapacityUah(Arrays.asList(
                point(0L, 40, true, 1_600_000L),
                point(4L * MINUTE, 42, true, 1_680_000L),
                point(10L * MINUTE, 45, true, 1_800_000L)));

        assertThat(estimate, is(4_000_000L));
    }

    @Test
    void rejectsDisconnectedRangesInsteadOfInventingCapacity() {
        long estimate = BatteryHistoryStore.estimateFullCapacityUah(Arrays.asList(
                point(0L, 80, false, 3_200_000L),
                point(46L * MINUTE, 75, false, 3_000_000L)));

        assertThat(estimate, is(-1L));
    }

    private static BatteryHistoryStore.SamplePoint point(long timestamp, int level,
                                                          boolean charging, long chargeUah) {
        return new BatteryHistoryStore.SamplePoint(timestamp, level, charging, true,
                Float.NaN, Long.MIN_VALUE, chargeUah);
    }
}
