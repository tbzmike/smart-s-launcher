package fr.neamar.kiss.forwarder;

/** Distinguishes a real touch-driven viewport move from a tap/press on a Vertical Card. */
final class VerticalCardUserScrollGesturePolicy {
    private boolean touchActive;
    private boolean movementReported;
    private int initialScrollY;

    void onTouchDown(int scrollY) {
        touchActive = true;
        movementReported = false;
        initialScrollY = scrollY;
    }

    /** Returns true exactly once when this gesture has actually changed the viewport scroll Y. */
    boolean onTouchMove(int scrollY) {
        if (!touchActive || movementReported || scrollY == initialScrollY) return false;
        movementReported = true;
        return true;
    }

    void onTouchEnd() {
        touchActive = false;
        movementReported = false;
    }
}
