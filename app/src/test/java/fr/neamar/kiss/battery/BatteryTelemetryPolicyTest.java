package fr.neamar.kiss.battery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

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
