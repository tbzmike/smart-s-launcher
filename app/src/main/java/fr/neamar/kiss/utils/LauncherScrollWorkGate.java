package fr.neamar.kiss.utils;

/**
 * Process-wide signal used by non-UI background features to stay out of the launcher's scroll path.
 *
 * This gate is intentionally simple: renderers only flip one volatile boolean. Background workers
 * consult it before starting optional work and services can pause recurring tasks while it is true.
 */
public final class LauncherScrollWorkGate {
    private static volatile boolean scrolling;

    private LauncherScrollWorkGate() { }

    public static void setScrolling(boolean value) {
        scrolling = value;
    }

    public static boolean isScrolling() {
        return scrolling;
    }
}
