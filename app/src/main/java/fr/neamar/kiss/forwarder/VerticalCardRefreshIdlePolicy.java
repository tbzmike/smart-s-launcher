package fr.neamar.kiss.forwarder;

/**
 * Frame-based gate for deferred Vertical Cards refreshes.
 *
 * A pending refresh never becomes runnable while a finger is down. After release, scroll movement
 * resets stability; two consecutive animation frames at the same Y establish that a fling has
 * actually settled without relying on an arbitrary millisecond delay. The gate also remembers
 * whether the viewport was at the newest/bottom edge when the data change arrived. That intent is
 * stable even if a newly inserted card later increases maxScrollY before the deferred rebuild.
 */
final class VerticalCardRefreshIdlePolicy {
    private static final int REQUIRED_STABLE_FRAMES = 2;

    private boolean pending;
    private boolean touching;
    private boolean keepBottom;
    private int lastScrollY;
    private int stableFrames;

    void request(int scrollY, boolean atBottom) {
        if (!pending) keepBottom = atBottom;
        else keepBottom |= atBottom;
        pending = true;
        lastScrollY = scrollY;
        stableFrames = 0;
    }

    void onTouchDown() {
        touching = true;
        stableFrames = 0;
    }

    void onTouchReleased(int scrollY) {
        touching = false;
        lastScrollY = scrollY;
        stableFrames = 0;
    }

    boolean shouldProbe() {
        return pending && !touching;
    }

    boolean onAnimationFrame(int scrollY) {
        if (!pending || touching) return false;
        if (scrollY != lastScrollY) {
            lastScrollY = scrollY;
            stableFrames = 0;
            return false;
        }
        stableFrames++;
        if (stableFrames < REQUIRED_STABLE_FRAMES) return false;
        pending = false;
        stableFrames = 0;
        return true;
    }

    boolean consumeKeepBottom() {
        boolean result = keepBottom;
        keepBottom = false;
        return result;
    }

    void clear() {
        pending = false;
        touching = false;
        keepBottom = false;
        stableFrames = 0;
    }
}
