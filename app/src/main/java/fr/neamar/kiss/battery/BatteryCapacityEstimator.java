package fr.neamar.kiss.battery;

import android.content.Context;
import android.content.res.Resources;

public final class BatteryCapacityEstimator {
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
}
