package fr.neamar.kiss.utils;

/**
 * Only an installed, verified distinct target may be enabled directly.
 * Never unfreeze an app based solely on a display-name or icon resemblance.
 */
public final class FrozenShortcutLaunchPolicy {
    private FrozenShortcutLaunchPolicy() { }

    public static boolean shouldEnableVerifiedTarget(boolean iceBoxPublisher,
            boolean currentUser, String publisher, String verifiedTarget,
            boolean targetInstalled, boolean targetEnabled) {
        return iceBoxPublisher && currentUser && targetInstalled && !targetEnabled
                && publisher != null && verifiedTarget != null && !verifiedTarget.isEmpty()
                && !publisher.equals(verifiedTarget);
    }
}
