package fr.neamar.kiss.forwarder;

/**
 * Vertical Cards keeps complex icon, avatar, text and click hierarchies per result.
 * Its own smaller viewport prevents UI-thread inflation/gradient/layout storms on Home.
 * This is deliberately independent of 3D Wheel and native Vertical List.
 */
final class CardHistoryWindow {
    static final int MAX_RENDERED_CARDS = 24;
    private static final int PAGE_STEP = 18;
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
        return Math.max(0, endExclusive - MAX_RENDERED_CARDS);
    }
    int end(int total) {
        sync(total);
        return endExclusive;
    }
    int size(int total) { return end(total) - start(total); }
    boolean hasOlder(int total) { return start(total) > 0; }
    boolean hasNewer(int total) { return end(total) < Math.max(0, total); }
    void older(int total) {
        sync(total);
        endExclusive = Math.max(Math.min(total, MAX_RENDERED_CARDS),
                endExclusive - PAGE_STEP);
    }
    void newer(int total) {
        sync(total);
        endExclusive = Math.min(total, endExclusive + PAGE_STEP);
    }
}
