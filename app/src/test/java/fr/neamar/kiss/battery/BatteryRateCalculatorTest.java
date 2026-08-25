package fr.neamar.kiss.battery;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

import android.os.BatteryManager;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

class BatteryRateCalculatorTest {
    private static final long MINUTE = 60_000L;
    private static final long CAPACITY_UAH = 4_000_000L;

    @Test
    void oneMinuteSamplesProduceARateAfterTwoMinutes() {
        BatterySnapshot current = snapshot(2L * MINUTE, 49, -400_000L, 1_986_666L);
        BatteryRateCalculator.ScreenRates rates = BatteryRateCalculator.calculate(Arrays.asList(
                point(0L, 50, -400_000L, 2_000_000L),
                point(MINUTE, 50, -400_000L, 1_993_333L),
                point(2L * MINUTE, 49, -400_000L, 1_986_666L)),
                current, CAPACITY_UAH, true);

        assertThat(rates.screenOnCurrentMa, closeTo(400.0, 1.0));
        assertThat(rates.screenOnPercentPerHour, closeTo(-10.0, 0.2));
    }

    @Test
    void chargingSamplesProduceAPositiveRate() {
        BatterySnapshot current = snapshot(2L * MINUTE, 51, 400_000L, 1_613_334L,
                BatteryManager.BATTERY_STATUS_CHARGING, Long.MIN_VALUE);
        BatteryRateCalculator.ScreenRates rates = BatteryRateCalculator.calculate(Arrays.asList(
                point(0L, 50, true, 400_000L, 1_600_000L),
                point(MINUTE, 50, true, 400_000L, 1_606_667L),
                point(2L * MINUTE, 51, true, 400_000L, 1_613_334L)),
                current, CAPACITY_UAH, true);

        assertThat(rates.screenOnCurrentMa, closeTo(400.0, 1.0));
        assertThat(rates.screenOnPercentPerHour, closeTo(10.0, 0.2));
    }

    @Test
    void sampledCurrentWorksWithoutChargeCounter() {
        BatterySnapshot current = snapshot(2L * MINUTE, 50, -400_000L, Long.MIN_VALUE);
        BatteryRateCalculator.ScreenRates rates = BatteryRateCalculator.calculate(Arrays.asList(
                point(0L, 50, -400_000L, Long.MIN_VALUE),
                point(MINUTE, 50, -400_000L, Long.MIN_VALUE),
                point(2L * MINUTE, 50, -400_000L, Long.MIN_VALUE)),
                current, CAPACITY_UAH, true);

        assertThat(rates.screenOnPercentPerHour, closeTo(-10.0, 0.01));
    }

    @Test
    void directLevelMovementWorksWithoutCurrentOrCounter() {
        BatterySnapshot current = snapshot(2L * MINUTE, 49, Long.MIN_VALUE, Long.MIN_VALUE);
        BatteryRateCalculator.ScreenRates rates = BatteryRateCalculator.calculate(Arrays.asList(
                point(0L, 50, Long.MIN_VALUE, Long.MIN_VALUE),
                point(MINUTE, 50, Long.MIN_VALUE, Long.MIN_VALUE),
                point(2L * MINUTE, 49, Long.MIN_VALUE, Long.MIN_VALUE)),
                current, -1L, true);

        assertThat(rates.screenOnPercentPerHour, closeTo(-30.0, 0.01));
    }

    @Test
    void liveCurrentIsShownBeforeHistoryExists() {
        BatterySnapshot current = snapshot(0L, 50, -400_000L, Long.MIN_VALUE);
        BatteryRateCalculator.ScreenRates rates = BatteryRateCalculator.calculate(
                Collections.emptyList(), current, CAPACITY_UAH, true);

        assertThat(rates.screenOnCurrentMa, closeTo(400.0, 0.01));
        assertThat(rates.screenOnPercentPerHour, closeTo(-10.0, 0.01));
        assertThat(Double.isNaN(rates.screenOnPercentPerHour), is(false));
    }

    @Test
    void hardwareAverageIsUsedWhenInstantaneousCurrentIsUnsupported() {
        BatterySnapshot current = snapshot(0L, 50, Long.MIN_VALUE, Long.MIN_VALUE,
                BatteryManager.BATTERY_STATUS_DISCHARGING, -300_000L);
        BatteryRateCalculator.ScreenRates rates = BatteryRateCalculator.calculate(
                Collections.emptyList(), current, CAPACITY_UAH, false);

        assertThat(rates.screenOffCurrentMa, closeTo(300.0, 0.01));
        assertThat(rates.screenOffPercentPerHour, closeTo(-7.5, 0.01));
    }

    private static BatteryHistoryStore.SamplePoint point(long timestamp, int level,
                                                          long currentUa, long chargeUah) {
        return point(timestamp, level, false, currentUa, chargeUah);
    }

    private static BatteryHistoryStore.SamplePoint point(long timestamp, int level,
                                                          boolean charging, long currentUa,
                                                          long chargeUah) {
        return new BatteryHistoryStore.SamplePoint(timestamp, level, charging, true,
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
