package fr.neamar.kiss.forwarder;

/**
 * Defines the two-stage launcher HOME contract without relying on Android lifecycle guesses.
 *
 * A HOME intent received while the launcher was in the background is the user's return to Home
 * and must keep/restore the position they left. A HOME intent received while the launcher is
 * already resumed is a second explicit press and must navigate to the newest bottom card.
 */
final class LauncherHomeNavigationPolicy {
    enum Action {
        RESTORE_LAST_POSITION,
        KEEP_CURRENT_POSITION,
        GO_TO_BOTTOM
    }

    private LauncherHomeNavigationPolicy() {
    }

    static Action actionForHomeIntent(boolean launcherWasForeground,
                                      boolean savedPositionAvailable) {
        if (launcherWasForeground) return Action.GO_TO_BOTTOM;
        return savedPositionAvailable
                ? Action.RESTORE_LAST_POSITION
                : Action.KEEP_CURRENT_POSITION;
    }
}
