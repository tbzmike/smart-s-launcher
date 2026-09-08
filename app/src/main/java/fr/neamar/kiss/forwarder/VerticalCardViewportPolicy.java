package fr.neamar.kiss.forwarder;

/**
 * State-only policy for deciding when Vertical Cards may control the viewport.
 *
 * Passive history/provider changes never arm viewport movement. Search/IME transitions retain their
 * existing bottom behavior. A deliberate second Home press or jump-to-latest tap creates a
 * persistent bottom pin that survives later geometry/data changes until the user starts a real
 * scroll gesture.
 */
final class VerticalCardViewportPolicy {
    private boolean searchActive;
    private boolean keyboardVisible;
    private boolean immediateBottomPending;
    private boolean forceBottomOnNextRebuild = true;
    private boolean rebuildBottomPending;
    private boolean explicitBottomPinned;

    void onSearchQueryChanged(boolean active, boolean changed) {
        searchActive = active;
        if (changed) {
            forceBottomOnNextRebuild = true;
            explicitBottomPinned = false;
        }
    }

    void requestImmediateBottom() {
        immediateBottomPending = true;
    }

    void requestPersistentBottom() {
        explicitBottomPinned = true;
        immediateBottomPending = true;
    }

    void requestBottomOnNextRebuild() {
        forceBottomOnNextRebuild = true;
    }

    void onUserScrollStarted() {
        explicitBottomPinned = false;
        immediateBottomPending = false;
        rebuildBottomPending = false;
        forceBottomOnNextRebuild = false;
    }

    boolean isPersistentBottomPinned() {
        return explicitBottomPinned;
    }

    void setKeyboardVisible(boolean visible) {
        keyboardVisible = visible;
    }

    boolean shouldBottomRebuild() {
        return searchActive
                || keyboardVisible
                || immediateBottomPending
                || explicitBottomPinned
                || forceBottomOnNextRebuild;
    }

    void onBottomRebuildStarted() {
        rebuildBottomPending = true;
    }

    boolean shouldPinGeometry() {
        return searchActive
                || keyboardVisible
                || immediateBottomPending
                || explicitBottomPinned
                || rebuildBottomPending;
    }

    /** Search, IME and explicit latest navigation outrank a saved history position. */
    boolean preventsPositionRestore() {
        return searchActive || keyboardVisible || immediateBottomPending || explicitBottomPinned;
    }

    void onBottomApplied() {
        immediateBottomPending = false;
        if (rebuildBottomPending) {
            rebuildBottomPending = false;
            forceBottomOnNextRebuild = false;
        }
    }

    /** A successful history-position restore settles only passive initial/rebuild bottom requests. */
    void onPositionRestoreApplied() {
        if (preventsPositionRestore()) return;
        rebuildBottomPending = false;
        forceBottomOnNextRebuild = false;
    }

    void resetForConfiguration() {
        immediateBottomPending = false;
        rebuildBottomPending = false;
        forceBottomOnNextRebuild = true;
        // explicitBottomPinned deliberately survives configuration/geometry changes.
    }
}
