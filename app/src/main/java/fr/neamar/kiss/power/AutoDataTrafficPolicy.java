package fr.neamar.kiss.power;

/**
 * Conservative, device-wide RX traffic protection before disabling mobile data.
 * Android does not publish a trustworthy download-running flag for every other app.
 * Treating substantial recent receive traffic as active may also protect streaming.
 */
public final class AutoDataTrafficPolicy {
    public static final long SAMPLE_MS = 60_000L;
    public static final long MIN_VALID_SAMPLE_MS = 15_000L;
    public static final long MIN_ACTIVE_RX_BYTES_PER_MINUTE = 128L * 1024L;
    public static final int CONFIRM_QUIET_WINDOWS = 2;

    public enum Traffic { UNKNOWN, ACTIVE, QUIET }

    private AutoDataTrafficPolicy() { }

    public static Traffic classify(long previousBytes, long currentBytes, long elapsedMs) {
        if (previousBytes < 0 || currentBytes < 0 || currentBytes < previousBytes
                || elapsedMs < MIN_VALID_SAMPLE_MS) return Traffic.UNKNOWN;
        long delta = currentBytes - previousBytes;
        long threshold = Math.max(32L * 1024L,
                (MIN_ACTIVE_RX_BYTES_PER_MINUTE * elapsedMs) / SAMPLE_MS);
        return delta >= threshold ? Traffic.ACTIVE : Traffic.QUIET;
    }

    public static boolean needsAnotherQuietWindow(boolean activityPreviouslyDetected,
                                                    int confirmedQuietWindows) {
        return activityPreviouslyDetected && confirmedQuietWindows < CONFIRM_QUIET_WINDOWS;
    }
}
