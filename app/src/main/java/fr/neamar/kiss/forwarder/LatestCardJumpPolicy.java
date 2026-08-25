package fr.neamar.kiss.forwarder;

/**
 * Pure visibility policy for the Vertical Cards jump-to-latest controls.
 *
 * The controls are useful only in settled history: search/IME/Home-driven bottom pinning remains
 * authoritative, and a newest card that is already fully inside the visible viewport needs no
 * shortcut. The comparison deliberately uses the card edge instead of the ScrollView's content
 * edge so an invisible trailing margin cannot keep the controls on screen.
 */
final class LatestCardJumpPolicy {
    private LatestCardJumpPolicy() {
    }

    static boolean shouldShow(boolean eligibleHistory,
                              boolean automaticBottomPending,
                              int cardCount,
                              int latestCardBottom,
                              int visibleViewportBottom,
                              int tolerancePx) {
        if (!eligibleHistory || automaticBottomPending || cardCount <= 0
                || latestCardBottom <= 0 || visibleViewportBottom <= 0) {
            return false;
        }
        int hiddenPixels = Math.max(0, latestCardBottom - visibleViewportBottom);
        return hiddenPixels > Math.max(0, tolerancePx);
    }
}
