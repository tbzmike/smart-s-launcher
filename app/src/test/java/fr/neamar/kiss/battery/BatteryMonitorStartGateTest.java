package fr.neamar.kiss.battery;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class BatteryMonitorStartGateTest {
    @Test
    void concurrentRequestsOnlyScheduleOneStart() {
        BatteryMonitorStartGate gate = new BatteryMonitorStartGate();

        assertThat(gate.requestStart(), is(true));
        assertThat(gate.requestStart(), is(false));
        assertThat(gate.isStartPending(), is(true));
    }

    @Test
    void activeServiceDoesNotReceiveDuplicateStartRequests() {
        BatteryMonitorStartGate gate = new BatteryMonitorStartGate();
        assertThat(gate.requestStart(), is(true));
        gate.onStartHandled();

        assertThat(gate.requestStart(), is(false));
        assertThat(gate.isStartPending(), is(false));
    }

    @Test
    void failedStartCanBeRetried() {
        BatteryMonitorStartGate gate = new BatteryMonitorStartGate();
        assertThat(gate.requestStart(), is(true));
        gate.onStartFailed();

        assertThat(gate.requestStart(), is(true));
    }

    @Test
    void stoppedServiceCanBeStartedAgain() {
        BatteryMonitorStartGate gate = new BatteryMonitorStartGate();
        assertThat(gate.requestStart(), is(true));
        gate.onStartHandled();
        gate.onServiceStopped();

        assertThat(gate.requestStart(), is(true));
    }
}
