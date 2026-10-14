package fr.neamar.kiss.forwarder;

/**
 * Bounded visual window for non-recycling History renderers (3D Wheel and Vertical Cards).
 * Indices remain adapter-absolute; paging never alters the shared History results or Vertical List.
 */
final class BoundedHistoryWindow {
    static final int VISIBLE_LIMIT = 64;
    private static final int PAGE_STEP = 48;
    private int endExclusive = -1;
    private int previousTotal = -1;

    void reset() {
        endExclusive = -1;
        previousTotal = -1;
    }

    void sync(int total) {
        total = Math.max(0, total);
        if (endExclusive < 0 || endExclusive >= previousTotal) {
            // Stay at the newest end when launches append to the underlying History.
            endExclusive = total;
        } else {
            endExclusive = Math.min(endExclusive, total);
        }
        previousTotal = total;
    }

    int start(int total) {
        sync(total);
        return Math.max(0, endExclusive - VISIBLE_LIMIT);
    }

    int end(int total) {
        sync(total);
        return endExclusive;
    }

    int size(int total) {
        return end(total) - start(total);
    }

    boolean hasOlder(int total) {
        return start(total) > 0;
    }

    boolean hasNewer(int total) {
        return end(total) < Math.max(0, total);
    }

    void older(int total) {
        sync(total);
        endExclusive = Math.max(Math.min(total, VISIBLE_LIMIT), endExclusive - PAGE_STEP);
    }

    void newer(int total) {
        sync(total);
        endExclusive = Math.min(total, endExclusive + PAGE_STEP);
    }
}
