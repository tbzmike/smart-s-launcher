package fr.neamar.kiss.battery;

import java.util.List;

/**
 * Calculates screen-on/off rates from observed charge-counter movement and elapsed time.
 * This avoids converting noisy instantaneous current through an unrelated learned capacity.
 */
public final class BatteryRateCalculator {
    private static final long MIN_SEGMENT_MS = 2L * 60_000L;
    private static final long MIN_STATE_OBSERVATION_MS = 4L * 60_000L;
    private static final long MAX_SEGMENT_MS = 30L * 60_000L;
    private static final long MIN_CAPACITY_UAH = 800_000L;
    private static final long MAX_CAPACITY_UAH = 12_000_000L;

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
        long durationMs;
        long deltaUah;
        double sampledCurrentMaMs;
        long sampledCurrentDurationMs;

        void addChargeDelta(long duration, long delta) {
            durationMs += duration;
            deltaUah += Math.abs(delta);
        }

        void addCurrent(long duration, long currentUa) {
            sampledCurrentMaMs += (Math.abs(currentUa) / 1000.0) * duration;
            sampledCurrentDurationMs += duration;
        }
    }

    private BatteryRateCalculator() { }

    public static ScreenRates calculate(BatteryHistoryStore store, BatterySnapshot current) {
        long capacityUah = impliedFullCapacityUah(current);
        if (capacityUah <= 0) {
            long learned = store.estimatedFullCapacityUah();
            if (validCapacity(learned)) capacityUah = learned;
        }

        List<BatteryHistoryStore.SamplePoint> points =
                store.recentSamples(48L * 60L * 60L * 1000L, 1500);
        if (points.size() < 2) return empty(capacityUah);

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

            if (previous.currentUa != Long.MIN_VALUE && next.currentUa != Long.MIN_VALUE) {
                long averageUa = (previous.currentUa / 2L) + (next.currentUa / 2L);
                bucket.addCurrent(duration, averageUa);
            }

            if (previous.chargeUah == Long.MIN_VALUE || next.chargeUah == Long.MIN_VALUE) continue;
            long delta = next.chargeUah - previous.chargeUah;
            if (delta == 0) continue;
            // Charge counter should rise while charging and fall while discharging. Ignore gauge jumps
            // that contradict the current session rather than turning them into a bogus rate.
            if (current.isCharging() ? delta < 0 : delta > 0) continue;
            bucket.addChargeDelta(duration, delta);
        }

        double onCurrent = currentFor(on);
        double offCurrent = currentFor(off);
        double onPercent = percentRateFor(on, capacityUah, current.isCharging());
        double offPercent = percentRateFor(off, capacityUah, current.isCharging());
        return new ScreenRates(onCurrent, onPercent, offCurrent, offPercent, capacityUah);
    }

    private static long impliedFullCapacityUah(BatterySnapshot current) {
        int percent = current.percent();
        if (current.chargeCounterUah == Long.MIN_VALUE || current.chargeCounterUah <= 0
                || percent < 5 || percent > 100) return -1L;
        long implied = Math.round(current.chargeCounterUah * (100.0 / percent));
        return validCapacity(implied) ? implied : -1L;
    }

    private static boolean validCapacity(long value) {
        return value >= MIN_CAPACITY_UAH && value <= MAX_CAPACITY_UAH;
    }

    private static double currentFor(Bucket bucket) {
        if (bucket.durationMs >= MIN_STATE_OBSERVATION_MS && bucket.deltaUah > 0) {
            double hours = bucket.durationMs / 3_600_000.0;
            return (bucket.deltaUah / 1000.0) / hours;
        }
        if (bucket.sampledCurrentDurationMs >= MIN_STATE_OBSERVATION_MS) {
            return bucket.sampledCurrentMaMs / bucket.sampledCurrentDurationMs;
        }
        return Double.NaN;
    }

    private static double percentRateFor(Bucket bucket, long capacityUah, boolean charging) {
        if (!validCapacity(capacityUah) || bucket.durationMs < MIN_STATE_OBSERVATION_MS
                || bucket.deltaUah <= 0) return Double.NaN;
        double hours = bucket.durationMs / 3_600_000.0;
        double percentPerHour = (bucket.deltaUah / (double) capacityUah) * 100.0 / hours;
        return charging ? Math.abs(percentPerHour) : -Math.abs(percentPerHour);
    }

    private static ScreenRates empty(long capacityUah) {
        return new ScreenRates(Double.NaN, Double.NaN, Double.NaN, Double.NaN, capacityUah);
    }
}
