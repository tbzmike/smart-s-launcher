package fr.neamar.kiss.battery;

import java.util.List;

/**
 * Calculates screen-on/off rates from the strongest signal a device exposes: observed
 * charge-counter movement, sampled current plus a bounded capacity estimate, or direct battery
 * level movement. Live current is used immediately until a long enough state bucket exists.
 */
public final class BatteryRateCalculator {
    // The service records active/charging samples every minute. Accept those intervals instead of
    // rejecting them with the old two-minute minimum, which kept active rates learning forever.
    private static final long MIN_SEGMENT_MS = 30_000L;
    private static final long MIN_STATE_OBSERVATION_MS = 2L * 60_000L;
    private static final long MAX_SEGMENT_MS = 30L * 60_000L;

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

        void addObservation(long duration, int delta) {
            observationDurationMs += duration;
            levelDelta += delta;
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
        if (points == null || points.size() < 2) {
            return withLiveFallback(empty(capacityUah), current, screenOn);
        }

        int start = points.size() - 1;
        while (start > 0 && points.get(start - 1).charging == current.isCharging()) start--;

        Bucket on = new Bucket();
        Bucket off = new Bucket();
        for (int i = start + 1; i < points.size(); i++) {
            BatteryHistoryStore.SamplePoint previous = points.get(i - 1);
            BatteryHistoryStore.SamplePoint next = points.get(i);
            if (previous.charging != current.isCharging() || next.charging != current.isCharging()) continue;
            if (previous.screenOn != next.screenOn) continue; // do not assign mixed intervals to either state

            long duration = next.ts - previous.ts;
            if (duration < MIN_SEGMENT_MS || duration > MAX_SEGMENT_MS) continue;
            Bucket bucket = previous.screenOn ? on : off;

            int levelDelta = next.level - previous.level;
            if (current.isCharging() ? levelDelta >= 0 : levelDelta <= 0) {
                bucket.addObservation(duration, levelDelta);
            }

            if (previous.currentUa != Long.MIN_VALUE && next.currentUa != Long.MIN_VALUE) {
                long averageUa = (previous.currentUa / 2L) + (next.currentUa / 2L);
                bucket.addCurrent(duration, averageUa);
            }

            if (previous.chargeUah == Long.MIN_VALUE || next.chargeUah == Long.MIN_VALUE) continue;
            long delta = next.chargeUah - previous.chargeUah;
            // Charge counter should rise while charging and fall while discharging. Ignore gauge jumps
            // that contradict the current session rather than turning them into a bogus rate.
            if (current.isCharging() ? delta < 0 : delta > 0) continue;
            bucket.addChargeDelta(duration, delta);
        }

        double onCurrent = currentFor(on);
        double offCurrent = currentFor(off);
        double onPercent = percentRateFor(on, capacityUah, current.isCharging());
        double offPercent = percentRateFor(off, capacityUah, current.isCharging());
        return withLiveFallback(new ScreenRates(onCurrent, onPercent, offCurrent, offPercent,
                capacityUah), current, screenOn);
    }

    private static double currentFor(Bucket bucket) {
        if (bucket.chargeDurationMs >= MIN_STATE_OBSERVATION_MS && bucket.chargeDeltaUah > 0) {
            double hours = bucket.chargeDurationMs / 3_600_000.0;
            return (bucket.chargeDeltaUah / 1000.0) / hours;
        }
        if (bucket.sampledCurrentDurationMs >= MIN_STATE_OBSERVATION_MS) {
            return bucket.sampledCurrentMaMs / bucket.sampledCurrentDurationMs;
        }
        return Double.NaN;
    }

    private static double percentRateFor(Bucket bucket, long capacityUah, boolean charging) {
        if (BatteryCapacityEstimator.isValidFullCapacityUah(capacityUah)
                && bucket.chargeDurationMs >= MIN_STATE_OBSERVATION_MS
                && bucket.chargeDeltaUah > 0) {
            double hours = bucket.chargeDurationMs / 3_600_000.0;
            double rate = (bucket.chargeDeltaUah / (double) capacityUah) * 100.0 / hours;
            return charging ? Math.abs(rate) : -Math.abs(rate);
        }

        double currentMa = currentFor(bucket);
        if (BatteryCapacityEstimator.isValidFullCapacityUah(capacityUah)
                && !Double.isNaN(currentMa)) {
            double rate = currentMa / (capacityUah / 1000.0) * 100.0;
            return charging ? Math.abs(rate) : -Math.abs(rate);
        }

        if (bucket.observationDurationMs >= MIN_STATE_OBSERVATION_MS
                && bucket.levelDelta != 0) {
            return bucket.levelDelta / (bucket.observationDurationMs / 3_600_000.0);
        }
        return Double.NaN;
    }

    private static ScreenRates withLiveFallback(ScreenRates rates, BatterySnapshot current,
                                                boolean screenOn) {
        double liveMa = current.sampleCurrentMa();
        if (Double.isNaN(liveMa)) return rates;
        double liveAbsMa = Math.abs(liveMa);
        double livePercent = BatteryCapacityEstimator.isValidFullCapacityUah(rates.capacityUah)
                ? liveAbsMa / (rates.capacityUah / 1000.0) * 100.0
                : Double.NaN;
        if (!Double.isNaN(livePercent) && !current.isCharging()) livePercent = -livePercent;

        if (screenOn) {
            return new ScreenRates(
                    Double.isNaN(rates.screenOnCurrentMa) ? liveAbsMa : rates.screenOnCurrentMa,
                    Double.isNaN(rates.screenOnPercentPerHour) ? livePercent
                            : rates.screenOnPercentPerHour,
                    rates.screenOffCurrentMa, rates.screenOffPercentPerHour, rates.capacityUah);
        }
        return new ScreenRates(rates.screenOnCurrentMa, rates.screenOnPercentPerHour,
                Double.isNaN(rates.screenOffCurrentMa) ? liveAbsMa : rates.screenOffCurrentMa,
                Double.isNaN(rates.screenOffPercentPerHour) ? livePercent
                        : rates.screenOffPercentPerHour,
                rates.capacityUah);
    }

    private static ScreenRates empty(long capacityUah) {
        return new ScreenRates(Double.NaN, Double.NaN, Double.NaN, Double.NaN, capacityUah);
    }
}
