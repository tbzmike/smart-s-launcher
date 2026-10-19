package fr.neamar.kiss.ui;

import android.view.MotionEvent;

/**
 * Passive horizontal swipe recognizer. Tracks raw screen coordinates so its
 * behaviour is identical for ListView, Vertical Cards and 3D Wheel.
 *
 * It never consumes DOWN or MOVE and never starts background work.
 */
public final class HomeDiscoverSwipeGesture {
    public static final String PREF_KEY = "home-swipe-right-google-discover";
    private final float minDistancePx;
    private boolean armed;
    private float downX;
    private float downY;
    private long downTime;

    public HomeDiscoverSwipeGesture(float density, int touchSlop) {
        minDistancePx = Math.max(72f * density, touchSlop * 5f);
    }

    public void reset() {
        armed = false;
    }

    /** True only on a completed, intentional right swipe from Home. */
    public boolean observe(MotionEvent event, boolean enabledOnHome) {
        if (event == null) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            armed = enabledOnHome && event.getPointerCount() == 1;
            downX = event.getRawX();
            downY = event.getRawY();
            downTime = event.getEventTime();
            return false;
        }
        if (!enabledOnHome || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            reset();
            return false;
        }
        if (!armed) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - downX;
            float dy = event.getRawY() - downY;
            if (Math.abs(dy) > minDistancePx && Math.abs(dy) > Math.abs(dx)) reset();
            return false;
        }
        if (action != MotionEvent.ACTION_UP) return false;
        boolean matched = isDiscoverSwipe(event.getRawX() - downX,
                event.getRawY() - downY, event.getEventTime() - downTime,
                minDistancePx);
        reset();
        return matched;
    }

    static boolean isDiscoverSwipe(float dx, float dy, long elapsedMs, float minDistancePx) {
        return dx >= minDistancePx
                && Math.abs(dy) <= Math.max(24f, dx * 0.55f)
                && elapsedMs >= 0L && elapsedMs <= 2000L;
    }
}
