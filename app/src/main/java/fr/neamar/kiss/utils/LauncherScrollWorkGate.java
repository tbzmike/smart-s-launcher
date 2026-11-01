package fr.neamar.kiss.utils;

/**
 * Process-wide signal used by non-UI background features to stay out of the launcher's scroll path.
 *
 * This gate is intentionally simple: renderers only flip one volatile boolean. Background workers
 * consult it before starting optional work and services can pause recurring tasks while it is true.
 */
public final class LauncherScrollWorkGate {
    private static volatile boolean scrolling;
    private static final Object IDLE = new Object();

    private LauncherScrollWorkGate() { }

    public static void setScrolling(boolean value) {
        synchronized (IDLE) {
            scrolling = value;
            if (!value) IDLE.notifyAll();
        }
    }

    public static boolean isScrolling() {
        return scrolling;
    }

    /** Sleep until the renderer signals idle, rather than waking every 40–80 ms. */
    public static boolean awaitIdle() {
        return awaitIdle(() -> true);
    }

    public static void signalPolicyChanged() {
        synchronized (IDLE) { IDLE.notifyAll(); }
    }

    public static boolean awaitIdle(java.util.function.BooleanSupplier allowed) {
        synchronized (IDLE) {
            while (scrolling && allowed.getAsBoolean()) {
                try { IDLE.wait(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return allowed.getAsBoolean() && !Thread.currentThread().isInterrupted();
        }
    }
}
