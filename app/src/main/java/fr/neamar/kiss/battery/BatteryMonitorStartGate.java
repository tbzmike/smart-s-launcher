package fr.neamar.kiss.battery;

/**
 * Process-local idempotency gate for foreground battery-service starts.
 * It prevents repeated widget/activity refreshes from restarting an active monitor.
 */
final class BatteryMonitorStartGate {
    private boolean startPending;
    private boolean serviceRunning;

    synchronized boolean requestStart() {
        if (startPending || serviceRunning) return false;
        startPending = true;
        return true;
    }

    synchronized void onStartHandled() {
        startPending = false;
        serviceRunning = true;
    }

    synchronized void onStartFailed() {
        startPending = false;
    }

    synchronized void onServiceStopped() {
        startPending = false;
        serviceRunning = false;
    }

    synchronized boolean isStartPending() {
        return startPending;
    }
}
