package fr.neamar.kiss.battery;

/** Event gating for noisy ACTION_BATTERY_CHANGED broadcasts. */
public final class BatteryBroadcastPolicy {
    private static final int TEMPERATURE_DELTA_TENTHS_C = 5;

    private BatteryBroadcastPolicy() { }

    public static boolean shouldSample(int oldPercent, int oldPlugged, int oldStatus,
                                       int oldTemperature, int percent, int plugged,
                                       int status, int temperature) {
        if (oldPercent != percent || oldPlugged != plugged || oldStatus != status) return true;
        return oldTemperature != Integer.MIN_VALUE && temperature != Integer.MIN_VALUE
                && Math.abs((long) temperature - oldTemperature) >= TEMPERATURE_DELTA_TENTHS_C;
    }
}
