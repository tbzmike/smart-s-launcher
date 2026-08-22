package fr.neamar.kiss.battery;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

public final class BatteryMonitorEngine {
    private BatteryMonitorEngine() {}

    public static BatterySnapshot read(Context context) {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery == null ? 100 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = battery == null ? BatteryManager.BATTERY_STATUS_UNKNOWN
                : battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int health = battery == null ? BatteryManager.BATTERY_HEALTH_UNKNOWN
                : battery.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
        int plugged = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int voltage = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        float temp = battery == null ? Float.NaN : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        long current = property(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        long avg = property(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
        long charge = property(manager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        long energy = property(manager, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
        long time = Long.MIN_VALUE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && manager != null) {
            try {
                long value = manager.computeChargeTimeRemaining();
                if (value >= 0) time = value;
            } catch (RuntimeException ignored) {
                time = Long.MIN_VALUE;
            }
        }
        int cycles = -1;
        if (Build.VERSION.SDK_INT >= 34 && battery != null) {
            cycles = battery.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1);
        }
        return new BatterySnapshot(System.currentTimeMillis(), level, scale, status, health, plugged,
                voltage, temp, current, avg, charge, energy, time, cycles);
    }

    private static long property(BatteryManager manager, int property) {
        if (manager == null) return Long.MIN_VALUE;
        try {
            long value = manager.getLongProperty(property);
            return value == Long.MIN_VALUE ? Long.MIN_VALUE : value;
        } catch (RuntimeException ignored) {
            return Long.MIN_VALUE;
        }
    }

    public static String sourceName(int plugged) {
        if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) return "Wireless";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) return "USB";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) return "AC";
        return "Battery";
    }

    public static String healthName(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheating";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over-voltage";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            default: return "Unknown";
        }
    }
}
