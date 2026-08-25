package fr.neamar.kiss.battery;

import android.content.Context;
import android.content.res.Resources;

public final class BatteryCapacityEstimator {
    private static final long MIN_CAPACITY_UAH = 300_000L;
    private static final long MAX_CAPACITY_UAH = 20_000_000L;

    public enum Source {
        OBSERVED,
        LIVE_CHARGE_COUNTER,
        DESIGN,
        UNAVAILABLE
    }

    public static final class Estimate {
        public final long fullCapacityUah;
        public final Source source;

        Estimate(long fullCapacityUah, Source source) {
            this.fullCapacityUah = fullCapacityUah;
            this.source = source;
        }

        public boolean isAvailable() {
            return fullCapacityUah > 0L;
        }

        public boolean supportsHealthEstimate() {
            return source == Source.OBSERVED || source == Source.LIVE_CHARGE_COUNTER;
        }
    }

    private BatteryCapacityEstimator() {}

    public static int designCapacityMah(Context context) {
        Resources resources = Resources.getSystem();
        int id = resources.getIdentifier("config_batteryCapacity", "integer", "android");
        if (id != 0) {
            try {
                int value = resources.getInteger(id);
                if (value > 300 && value < 20000) return value;
            } catch (Resources.NotFoundException ignored) { }
        }
        return -1;
    }

    public static double healthPercent(Context context, long estimatedFullUah) {
        int design = designCapacityMah(context);
        if (design <= 0 || estimatedFullUah <= 0) return Double.NaN;
        return Math.max(0.0, Math.min(150.0, estimatedFullUah / 1000.0 * 100.0 / design));
    }

    public static Estimate resolve(Context context, long observedFullUah,
                                   BatterySnapshot current) {
        return resolve(observedFullUah, current.chargeCounterUah, current.percent(),
                designCapacityMah(context));
    }

    static Estimate resolve(long observedFullUah, long chargeCounterUah,
                            int percent, int designCapacityMah) {
        if (isValidFullCapacityUah(observedFullUah)) {
            return new Estimate(observedFullUah, Source.OBSERVED);
        }

        long live = impliedFullCapacityUah(chargeCounterUah, percent);
        if (isValidFullCapacityUah(live)) {
            return new Estimate(live, Source.LIVE_CHARGE_COUNTER);
        }

        long designUah = designCapacityMah > 0 ? designCapacityMah * 1000L : -1L;
        if (isValidFullCapacityUah(designUah)) {
            return new Estimate(designUah, Source.DESIGN);
        }
        return new Estimate(-1L, Source.UNAVAILABLE);
    }

    static long impliedFullCapacityUah(long chargeCounterUah, int percent) {
        if (chargeCounterUah == Long.MIN_VALUE || chargeCounterUah <= 0L
                || percent < 5 || percent > 100) return -1L;
        long implied = Math.round(chargeCounterUah * (100.0 / percent));
        return isValidFullCapacityUah(implied) ? implied : -1L;
    }

    static boolean isValidFullCapacityUah(long value) {
        return value >= MIN_CAPACITY_UAH && value <= MAX_CAPACITY_UAH;
    }
}
