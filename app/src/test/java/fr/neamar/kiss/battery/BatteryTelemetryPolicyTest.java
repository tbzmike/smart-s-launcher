package fr.neamar.kiss.battery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

public final class BatteryTelemetryPolicyTest {
    @Test public void excludesOnlyOwnLiveTelemetry() {
        assertTrue(BatteryTelemetryPolicy.isOwnLiveNotification(
                "com.tbzmike.smartslauncher", "com.tbzmike.smartslauncher", 8450, "smart_battery_live"));
        assertFalse(BatteryTelemetryPolicy.isOwnLiveNotification(
                "com.tbzmike.smartslauncher", "other.app", 8450, "smart_battery_live"));
        assertFalse(BatteryTelemetryPolicy.isOwnLiveNotification(
                "com.tbzmike.smartslauncher", "com.tbzmike.smartslauncher", 8451, "smart_battery_alerts"));
        assertFalse(BatteryTelemetryPolicy.isOwnLiveNotification(
                "com.tbzmike.smartslauncher", "com.tbzmike.smartslauncher", 8450, "other"));
    }
}
