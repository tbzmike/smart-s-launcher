package fr.neamar.kiss.forwarder;

/**
 * State-only policy for deciding when Vertical Cards must be pinned to the bottom.
 *
 * Keeping this separate from Android views makes the navigation/search invariants executable in
 * unit tests. A search remains bottom-pinned across every result or geometry change. Query changes
 * also force the first rebuilt result set to the bottom, while Home is an immediate one-shot jump.
 */
final class VerticalCardViewportPolicy {
    private boolean searchActive;
    private boolean keyboardVisible;
    private boolean immediateBottomPending;
    private boolean forceBottomOnNextRebuild = true;
    private boolean rebuildBottomPending;

    void onSearchQueryChanged(boolean active, boolean changed) {
        searchActive = active;
        if (changed) forceBottomOnNextRebuild = true;
    }

    void requestImmediateBottom() {
        immediateBottomPending = true;
    }

    void setKeyboardVisible(boolean visible) {
        keyboardVisible = visible;
    }

    boolean shouldBottomRebuild() {
        return searchActive
                || keyboardVisible
                || immediateBottomPending
                || forceBottomOnNextRebuild;
    }

    void onBottomRebuildStarted() {
        rebuildBottomPending = true;
    }

    boolean shouldPinGeometry() {
        return searchActive
                || keyboardVisible
                || immediateBottomPending
                || rebuildBottomPending;
    }

    void onBottomApplied() {
        immediateBottomPending = false;
        if (rebuildBottomPending) {
            rebuildBottomPending = false;
            forceBottomOnNextRebuild = false;
        }
    }

    void resetForConfiguration() {
        immediateBottomPending = false;
        rebuildBottomPending = false;
        forceBottomOnNextRebuild = true;
    }
}
