package fr.neamar.kiss.battery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class BatteryBroadcastPolicyTest {
    @Test public void ignoresIdenticalAndTinyThermalUpdates() {
        assertFalse(BatteryBroadcastPolicy.shouldSample(72, 0, 3, 315, 72, 0, 3, 315));
        assertFalse(BatteryBroadcastPolicy.shouldSample(72, 0, 3, 315, 72, 0, 3, 319));
    }
    @Test public void samplesMeaningfulThermalChanges() {
        assertTrue(BatteryBroadcastPolicy.shouldSample(72, 0, 3, 315, 72, 0, 3, 320));
    }
    @Test public void samplesPercentagePlugAndStatusChanges() {
        assertTrue(BatteryBroadcastPolicy.shouldSample(72, 0, 3, 315, 71, 0, 3, 315));
        assertTrue(BatteryBroadcastPolicy.shouldSample(72, 0, 3, 315, 72, 1, 3, 315));
        assertTrue(BatteryBroadcastPolicy.shouldSample(72, 0, 3, 315, 72, 0, 2, 315));
    }
    @Test public void missingTemperatureDoesNotGenerateWork() {
        assertFalse(BatteryBroadcastPolicy.shouldSample(72, 0, 3, 315, 72, 0, 3, Integer.MIN_VALUE));
    }
}
