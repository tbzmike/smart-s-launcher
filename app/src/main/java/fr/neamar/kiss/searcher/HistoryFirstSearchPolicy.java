package fr.neamar.kiss.searcher;

/** User-settings-driven two-stage search policy. No timers or worker threads are created here. */
public final class HistoryFirstSearchPolicy {
    /** Full provider/database search begins only after the last input has been idle for five seconds. */
    public static final long DEEP_SEARCH_IDLE_DELAY_MS = 5_000L;

    private HistoryFirstSearchPolicy() { }

    /** Respect the live History setting; zero disables saved-history preview. */
    public static int seedLimit(int configuredHistoryItems) {
        return Math.max(0, configuredHistoryItems);
    }
}
