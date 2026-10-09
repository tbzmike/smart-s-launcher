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
}
