package fr.neamar.kiss.battery;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

import android.os.BatteryManager;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class BatteryRateCalculatorTest {
    private static final long MINUTE = 60_000L;
    private static final long CAPACITY_UAH = 4_000_000L;

    @Test
    void shortObservationDoesNotInventHourlyRate() {
        BatterySnapshot current = snapshot(2L * MINUTE, 50, -400_000L, 1_986_666L);
        BatteryRateCalculator.ScreenRates rates = BatteryRateCalculator.calculate(Arrays.asList(
                point(0L, 50, true, -400_000L, 2_000_000L),
                point(MINUTE, 50, true, -400_000L, 1_993_333L),
                point(2L * MINUTE, 50, true, -400_000L, 1_986_666L)),
                current, CAPACITY_UAH, true);

        assertThat(Double.isNaN(rates.screenOnCurrentMa), is(true));
        assertThat(Double.isNaN(rates.screenOnPercentPerHour), is(true));
    }

    @Test
    void settledScreenOnObservationProducesMeasuredRate() {
        List<BatteryHistoryStore.SamplePoint> points = new ArrayList<>();
        long startCharge = 2_000_000L;
        for (int minute = 0; minute <= 16; minute++) {
            points.add(point(minute * MINUTE, 50, true, -100_000L,
                    startCharge - minute * 1_667L));
        }
        BatterySnapshot current = snapshot(16L * MINUTE, 50, -100_000L,
                startCharge - 16L * 1_667L);

        BatteryRateCalculator.ScreenRates rates = BatteryRateCalculator.calculate(
                points, current, CAPACITY_UAH, true);

        assertThat(rates.screenOnCurrentMa, closeTo(100.0, 1.0));
        assertThat(rates.screenOnPercentPerHour, closeTo(-2.5, 0.1));
    }

    @Test
    void screenOffTransitionSpikeIsExcludedFromStandbyRate() {
        List<BatteryHistoryStore.SamplePoint> points = new ArrayList<>();
        points.add(point(0L, 90, true, -100_000L, 3_600_000L));
        points.add(point(1L * MINUTE, 90, false, -900_000L, 3_590_000L));
        points.add(point(4L * MINUTE, 89, false, -900_000L, 3_400_000L));
        points.add(point(7L * MINUTE, 88, false, -900_000L, 3_300_000L));

        long charge = 3_300_000L;
        for (int minute = 10; minute <= 28; minute += 3) {
            charge -= 10_000L;
            points.add(point(minute * MINUTE, 88, false, -200_000L, charge));
        }
        BatterySnapshot current = snapshot(28L * MINUTE, 88, -200_000L, charge);

        BatteryRateCalculator.ScreenRates rates = BatteryRateCalculator.calculate(
                points, current, CAPACITY_UAH, false);

        assertThat(rates.screenOffCurrentMa, closeTo(200.0, 1.0));
        assertThat(rates.screenOffPercentPerHour, closeTo(-5.0, 0.1));
    }

    @Test
    void realScreenOffDrainMayExceedScreenOnAfterEnoughEvidence() {
        List<BatteryHistoryStore.SamplePoint> points = new ArrayList<>();
        long charge = 3_600_000L;

        for (int minute = 0; minute <= 16; minute++) {
            points.add(point(minute * MINUTE, 90, true, -100_000L, charge));
            charge -= 1_667L;
        }

        points.add(point(17L * MINUTE, 89, false, -400_000L, charge));
        for (int minute = 20; minute <= 44; minute += 3) {
            charge -= 20_000L;
            points.add(point(minute * MINUTE, 89, false, -400_000L, charge));
        }
        BatterySnapshot current = snapshot(44L * MINUTE, 89, -400_000L, charge);

        BatteryRateCalculator.ScreenRates rates = BatteryRateCalculator.calculate(
                points, current, CAPACITY_UAH, false);

        assertThat(rates.screenOnPercentPerHour, closeTo(-2.5, 0.1));
        assertThat(rates.screenOffPercentPerHour, closeTo(-10.0, 0.1));
        assertThat(Math.abs(rates.screenOffPercentPerHour)
                > Math.abs(rates.screenOnPercentPerHour), is(true));
    }

    @Test
    void chargingRateAlsoRequiresSettledObservation() {
        List<BatteryHistoryStore.SamplePoint> points = new ArrayList<>();
        long startCharge = 1_600_000L;
        for (int minute = 0; minute <= 16; minute++) {
            points.add(point(minute * MINUTE, 40, true, true, 400_000L,
                    startCharge + minute * 6_667L));
        }
        BatterySnapshot current = snapshot(16L * MINUTE, 42, 400_000L,
                startCharge + 16L * 6_667L,
                BatteryManager.BATTERY_STATUS_CHARGING, Long.MIN_VALUE);

        BatteryRateCalculator.ScreenRates rates = BatteryRateCalculator.calculate(
                points, current, CAPACITY_UAH, true);

        assertThat(rates.screenOnCurrentMa, closeTo(400.0, 1.0));
        assertThat(rates.screenOnPercentPerHour, closeTo(10.0, 0.2));
    }

    @Test
    void liveCurrentDoesNotBecomePercentRateWithoutHistory() {
        BatterySnapshot current = snapshot(0L, 50, -400_000L, Long.MIN_VALUE);
        BatteryRateCalculator.ScreenRates rates = BatteryRateCalculator.calculate(
                Collections.emptyList(), current, CAPACITY_UAH, true);

        assertThat(Double.isNaN(rates.screenOnCurrentMa), is(true));
        assertThat(Double.isNaN(rates.screenOnPercentPerHour), is(true));
    }

    private static BatteryHistoryStore.SamplePoint point(long timestamp, int level,
                                                          boolean screenOn,
                                                          long currentUa, long chargeUah) {
        return point(timestamp, level, false, screenOn, currentUa, chargeUah);
    }

    private static BatteryHistoryStore.SamplePoint point(long timestamp, int level,
                                                          boolean charging, boolean screenOn,
                                                          long currentUa, long chargeUah) {
        return new BatteryHistoryStore.SamplePoint(timestamp, level, charging, screenOn,
                Float.NaN, currentUa, chargeUah);
    }

    private static BatterySnapshot snapshot(long timestamp, int level,
                                             long currentUa, long chargeUah) {
        return snapshot(timestamp, level, currentUa, chargeUah,
                BatteryManager.BATTERY_STATUS_DISCHARGING, Long.MIN_VALUE);
    }

    private static BatterySnapshot snapshot(long timestamp, int level,
                                             long currentUa, long chargeUah,
                                             int status, long averageCurrentUa) {
        return new BatterySnapshot(timestamp, level, 100,
                status,
                BatteryManager.BATTERY_HEALTH_GOOD, 0, 4_000, 25f,
                currentUa, averageCurrentUa, chargeUah, Long.MIN_VALUE,
                Long.MIN_VALUE, -1);
    }
}
