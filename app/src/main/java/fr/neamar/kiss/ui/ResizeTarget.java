package fr.neamar.kiss.ui;

/**
 * Marker/action used by launcher surfaces that can enter a temporary widget-style resize mode.
 */
@FunctionalInterface
public interface ResizeTarget {
    void beginResize();
}
