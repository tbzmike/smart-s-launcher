package fr.neamar.kiss.appusage;

/** Pure policy for deciding whether App Usage found an update missed by the live package receiver. */
public final class AppMetadataRefreshPolicy {
    private AppMetadataRefreshPolicy() { }

    public static boolean shouldQueueFromReconciliation(
            long previousLastUpdateMs,
            long currentLastUpdateMs,
            long firstInstallMs) {
        return previousLastUpdateMs > 0L
                && currentLastUpdateMs > previousLastUpdateMs
                && currentLastUpdateMs > firstInstallMs + 1_000L;
    }

    /** ADDED(replacing) and REPLACED describe the same installed package revision. */
    public static boolean shouldQueuePackageRevision(
            long observedUpdateMs, long lastQueuedUpdateMs, boolean alreadyPending) {
        if (observedUpdateMs > 0L) return observedUpdateMs != lastQueuedUpdateMs;
        return !alreadyPending;
    }

    /** Completion of an older fetch must not erase a newer update queued during that fetch. */
    public static boolean canAcknowledge(long currentToken, long completedToken) {
        return completedToken > 0L && currentToken == completedToken;
    }
}
