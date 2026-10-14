package fr.neamar.kiss.forwarder;

/**
 * A deliberately small, Wheel-only visual window.
 * Unlike Vertical Cards (64 views) the 3D transform renderer holds expensive
 * animated View hierarchies. Never inflate the whole History or alter its adapter.
 */
final class WheelHistoryWindow {
    static final int MAX_ATTACHED_ROWS = 12;
    private static final int PAGE_STEP = 9;
    private int endExclusive = -1;
    private int previousTotal = -1;

    void reset() {
        endExclusive = -1;
        previousTotal = -1;
    }

    private void sync(int total) {
        total = Math.max(0, total);
        if (endExclusive < 0 || endExclusive >= previousTotal) {
            endExclusive = total;
        } else {
            endExclusive = Math.min(endExclusive, total);
        }
        previousTotal = total;
    }

    int start(int total) {
        sync(total);
        return Math.max(0, endExclusive - MAX_ATTACHED_ROWS);
    }

    int end(int total) {
        sync(total);
        return endExclusive;
    }

    boolean hasOlder(int total) { return start(total) > 0; }
    boolean hasNewer(int total) { return end(total) < Math.max(0, total); }

    void older(int total) {
        sync(total);
        endExclusive = Math.max(Math.min(total, MAX_ATTACHED_ROWS), endExclusive - PAGE_STEP);
    }

    void newer(int total) {
        sync(total);
        endExclusive = Math.min(total, endExclusive + PAGE_STEP);
    }
}
