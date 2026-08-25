package fr.neamar.kiss.battery;

import java.util.List;

/**
 * Calculates screen-on/off rates from settled, state-specific observations. Instantaneous current
 * remains useful as a live reading, but is deliberately not converted into a learned hourly drain
 * rate because a brief current spike is not representative of sustained screen-on/off behaviour.
 */
public final class BatteryRateCalculator {
    private static final long MIN_SEGMENT_MS = 30_000L;
    private static final long MAX_SEGMENT_MS = 30L * 60_000L;

    // Screen transitions are noisy: UI work, radios, notifications and suspend entry can continue
    // for several minutes after the display changes state. Exclude that transition window entirely.
    private static final long STATE_SETTLE_MS = 5L * 60_000L;

    // The service samples once per minute while interactive and normally every three minutes while
    // screen-off. These windows require several post-settle samples before an hourly rate is trusted.
    private static final long MIN_SCREEN_ON_OBSERVATION_MS = 10L * 60_000L;
    private static final long MIN_SCREEN_OFF_OBSERVATION_MS = 20L * 60_000L;
    private static final int MIN_VALID_SEGMENTS = 3;

    public static final class ScreenRates {
        public final double screenOnCurrentMa;
        public final double screenOnPercentPerHour;
        public final double screenOffCurrentMa;
        public final double screenOffPercentPerHour;
        public final long capacityUah;

        ScreenRates(double screenOnCurrentMa, double screenOnPercentPerHour,
                    double screenOffCurrentMa, double screenOffPercentPerHour,
                    long capacityUah) {
            this.screenOnCurrentMa = screenOnCurrentMa;
            this.screenOnPercentPerHour = screenOnPercentPerHour;
            this.screenOffCurrentMa = screenOffCurrentMa;
            this.screenOffPercentPerHour = screenOffPercentPerHour;
            this.capacityUah = capacityUah;
        }
    }

    private static final class Bucket {
        long observationDurationMs;
        long chargeDurationMs;
        long chargeDeltaUah;
        int levelDelta;
        double sampledCurrentMaMs;
        long sampledCurrentDurationMs;
        int validSegments;

        void addObservation(long duration, int delta) {
            observationDurationMs += duration;
            levelDelta += delta;
            validSegments++;
        }

        void addChargeDelta(long duration, long delta) {
            chargeDurationMs += duration;
            chargeDeltaUah += Math.abs(delta);
        }

        void addCurrent(long duration, long currentUa) {
            sampledCurrentMaMs += (Math.abs(currentUa) / 1000.0) * duration;
            sampledCurrentDurationMs += duration;
        }
    }

    private BatteryRateCalculator() { }

    public static ScreenRates calculate(BatteryHistoryStore store, BatterySnapshot current) {
        long observed = store.estimatedFullCapacityUah();
        long implied = BatteryCapacityEstimator.impliedFullCapacityUah(
                current.chargeCounterUah, current.percent());
        long capacityUah = BatteryCapacityEstimator.isValidFullCapacityUah(observed)
                ? observed : implied;
        List<BatteryHistoryStore.SamplePoint> points =
                store.recentSamples(48L * 60L * 60L * 1000L, 1500);
        boolean screenOn = points.isEmpty() || points.get(points.size() - 1).screenOn;
        return calculate(points, current, capacityUah, screenOn);
    }

    public static ScreenRates calculate(BatteryHistoryStore store, BatterySnapshot current,
                                        long capacityUah, boolean screenOn) {
        List<BatteryHistoryStore.SamplePoint> points =
                store.recentSamples(48L * 60L * 60L * 1000L, 1500);
        return calculate(points, current, capacityUah, screenOn);
    }

    static ScreenRates calculate(List<BatteryHistoryStore.SamplePoint> points,
                                 BatterySnapshot current, long capacityUah,
                                 boolean screenOn) {
        if (!BatteryCapacityEstimator.isValidFullCapacityUah(capacityUah)) {
            capacityUah = BatteryCapacityEstimator.impliedFullCapacityUah(
                    current.chargeCounterUah, current.percent());
        }
        if (points == null || points.size() < 2) return empty(capacityUah);

        int start = points.size() - 1;
        while (start > 0 && points.get(start - 1).charging == current.isCharging()) start--;

        Bucket on = new Bucket();
        Bucket off = new Bucket();
        long stateEnteredAt = points.get(start).ts;
        boolean stateScreenOn = points.get(start).screenOn;

        for (int i = start + 1; i < points.size(); i++) {
            BatteryHistoryStore.SamplePoint previous = points.get(i - 1);
            BatteryHistoryStore.SamplePoint next = points.get(i);
            if (previous.charging != current.isCharging() || next.charging != current.isCharging()) continue;

            if (next.screenOn != stateScreenOn) {
                stateScreenOn = next.screenOn;
                stateEnteredAt = next.ts;
            }
            if (previous.screenOn != next.screenOn) continue;

            long duration = next.ts - previous.ts;
            if (duration < MIN_SEGMENT_MS || duration > MAX_SEGMENT_MS) continue;

            // Do not let the interval straddle the settling boundary. The first accepted interval
            // starts only after this state has already been stable for STATE_SETTLE_MS.
            if (previous.ts - stateEnteredAt < STATE_SETTLE_MS) continue;

            Bucket bucket = previous.screenOn ? on : off;
            int levelDelta = next.level - previous.level;
            if (current.isCharging() ? levelDelta >= 0 : levelDelta <= 0) {
                bucket.addObservation(duration, levelDelta);
            } else {
                // A level move opposite to the session direction usually means gauge correction.
                // Exclude the whole interval from learned state rates.
                continue;
            }

            if (previous.currentUa != Long.MIN_VALUE && next.currentUa != Long.MIN_VALUE) {
                long averageUa = (previous.currentUa / 2L) + (next.currentUa / 2L);
                bucket.addCurrent(duration, averageUa);
            }

            if (previous.chargeUah == Long.MIN_VALUE || next.chargeUah == Long.MIN_VALUE) continue;
            long delta = next.chargeUah - previous.chargeUah;
            if (current.isCharging() ? delta < 0 : delta > 0) continue;
            bucket.addChargeDelta(duration, delta);
        }

        double onCurrent = currentFor(on, true);
        double offCurrent = currentFor(off, false);
        double onPercent = percentRateFor(on, capacityUah, current.isCharging(), true);
        double offPercent = percentRateFor(off, capacityUah, current.isCharging(), false);
        return new ScreenRates(onCurrent, onPercent, offCurrent, offPercent, capacityUah);
    }

    private static long minimumObservationMs(boolean screenOn) {
        return screenOn ? MIN_SCREEN_ON_OBSERVATION_MS : MIN_SCREEN_OFF_OBSERVATION_MS;
    }

    private static boolean hasEnoughObservation(Bucket bucket, boolean screenOn) {
        return bucket.validSegments >= MIN_VALID_SEGMENTS
                && bucket.observationDurationMs >= minimumObservationMs(screenOn);
    }

    private static double currentFor(Bucket bucket, boolean screenOn) {
        if (!hasEnoughObservation(bucket, screenOn)) return Double.NaN;
        long minimum = minimumObservationMs(screenOn);
        if (bucket.chargeDurationMs >= minimum && bucket.chargeDeltaUah > 0) {
            double hours = bucket.chargeDurationMs / 3_600_000.0;
            return (bucket.chargeDeltaUah / 1000.0) / hours;
        }
        if (bucket.sampledCurrentDurationMs >= minimum) {
            return bucket.sampledCurrentMaMs / bucket.sampledCurrentDurationMs;
        }
        return Double.NaN;
    }

    private static double percentRateFor(Bucket bucket, long capacityUah, boolean charging,
                                         boolean screenOn) {
        if (!hasEnoughObservation(bucket, screenOn)) return Double.NaN;
        long minimum = minimumObservationMs(screenOn);

        // Prefer the hardware charge counter. It directly measures charge movement and is much less
        // sensitive to integer battery-level rounding than a short percentage sample.
        if (BatteryCapacityEstimator.isValidFullCapacityUah(capacityUah)
                && bucket.chargeDurationMs >= minimum
                && bucket.chargeDeltaUah > 0) {
            double hours = bucket.chargeDurationMs / 3_600_000.0;
            double rate = (bucket.chargeDeltaUah / (double) capacityUah) * 100.0 / hours;
            return charging ? Math.abs(rate) : -Math.abs(rate);
        }

        // If the charge counter is unavailable, fall back only to actual observed battery-level
        // movement. Never turn CURRENT_NOW into a percent/hour estimate.
        if (bucket.observationDurationMs >= minimum && bucket.levelDelta != 0) {
            return bucket.levelDelta / (bucket.observationDurationMs / 3_600_000.0);
        }
        return Double.NaN;
    }

    private static ScreenRates empty(long capacityUah) {
        return new ScreenRates(Double.NaN, Double.NaN, Double.NaN, Double.NaN, capacityUah);
    }
}
