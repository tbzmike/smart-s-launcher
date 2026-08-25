package fr.neamar.kiss.battery;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class BatteryCapacityEstimatorTest {
    @Test
    void observedCapacityAlwaysWins() {
        BatteryCapacityEstimator.Estimate estimate = BatteryCapacityEstimator.resolve(
                3_800_000L, 2_000_000L, 50, 4_500);

        assertThat(estimate.fullCapacityUah, is(3_800_000L));
        assertThat(estimate.source, is(BatteryCapacityEstimator.Source.OBSERVED));
    }

    @Test
    void liveChargeCounterFinishesTheEstimateImmediately() {
        BatteryCapacityEstimator.Estimate estimate = BatteryCapacityEstimator.resolve(
                -1L, 2_000_000L, 50, 4_500);

        assertThat(estimate.fullCapacityUah, is(4_000_000L));
        assertThat(estimate.source, is(BatteryCapacityEstimator.Source.LIVE_CHARGE_COUNTER));
    }

    @Test
    void designCapacityIsATerminalFallbackInsteadOfPermanentLearning() {
        BatteryCapacityEstimator.Estimate estimate = BatteryCapacityEstimator.resolve(
                -1L, Long.MIN_VALUE, 50, 4_500);

        assertThat(estimate.fullCapacityUah, is(4_500_000L));
        assertThat(estimate.source, is(BatteryCapacityEstimator.Source.DESIGN));
        assertThat(estimate.supportsHealthEstimate(), is(false));
    }

    @Test
    void unsupportedHardwareHasAnExplicitUnavailableState() {
        BatteryCapacityEstimator.Estimate estimate = BatteryCapacityEstimator.resolve(
                -1L, Long.MIN_VALUE, -1, -1);

        assertThat(estimate.isAvailable(), is(false));
        assertThat(estimate.source, is(BatteryCapacityEstimator.Source.UNAVAILABLE));
    }
}
