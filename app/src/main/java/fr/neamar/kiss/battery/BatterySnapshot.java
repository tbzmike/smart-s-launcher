package fr.neamar.kiss.battery;

public final class BatterySnapshot {
    public final long timestamp;
    public final int level;
    public final int scale;
    public final int status;
    public final int health;
    public final int plugged;
    public final int voltageMv;
    public final float temperatureC;
    public final long currentUa;
    public final long averageCurrentUa;
    public final long chargeCounterUah;
    public final long energyNwh;
    public final long chargeTimeRemainingMs;
    public final int cycleCount;

    BatterySnapshot(long timestamp, int level, int scale, int status, int health, int plugged,
                    int voltageMv, float temperatureC, long currentUa, long averageCurrentUa,
                    long chargeCounterUah, long energyNwh, long chargeTimeRemainingMs,
                    int cycleCount) {
        this.timestamp = timestamp;
        this.level = level;
        this.scale = scale;
        this.status = status;
        this.health = health;
        this.plugged = plugged;
        this.voltageMv = voltageMv;
        this.temperatureC = temperatureC;
        this.currentUa = currentUa;
        this.averageCurrentUa = averageCurrentUa;
        this.chargeCounterUah = chargeCounterUah;
        this.energyNwh = energyNwh;
        this.chargeTimeRemainingMs = chargeTimeRemainingMs;
        this.cycleCount = cycleCount;
    }

    public int percent() {
        return scale > 0 ? Math.max(0, Math.min(100, Math.round(level * 100f / scale))) : level;
    }

    public boolean isCharging() {
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
    }

    public double currentMa() {
        return currentUa == Long.MIN_VALUE ? Double.NaN : currentUa / 1000.0;
    }

    public double averageCurrentMa() {
        return averageCurrentUa == Long.MIN_VALUE ? Double.NaN : averageCurrentUa / 1000.0;
    }

    public double powerW() {
        if (currentUa == Long.MIN_VALUE || voltageMv <= 0) return Double.NaN;
        return Math.abs(currentUa / 1_000_000.0) * (voltageMv / 1000.0);
    }
}
