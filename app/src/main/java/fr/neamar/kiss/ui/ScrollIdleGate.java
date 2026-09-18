package fr.neamar.kiss.ui;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Defers non-scroll work until a touch/scroll viewport has been motionless for a short interval.
 * Scroll callbacks only update this gate; they never traverse or mutate rendered children.
 */
public final class ScrollIdleGate {
    private static final long IDLE_DELAY_MS = 180L;

    private final View host;
    private final Set<Runnable> pending = new LinkedHashSet<>();
    private final List<Runnable> scrollStartedListeners = new ArrayList<>();
    private boolean touching;
    private boolean scrolling;
    private boolean destroyed;
    private boolean settleScheduled;
    private long lastMotionUptime;

    private final Runnable settleRunnable = () -> {
        settleScheduled = false;
        if (destroyed || touching) return;

        long elapsed = Math.max(0L, SystemClock.uptimeMillis() - lastMotionUptime);
        if (elapsed < IDLE_DELAY_MS) {
            scheduleIdleCheck(IDLE_DELAY_MS - elapsed);
            return;
        }

        scrolling = false;
        if (pending.isEmpty()) return;

        List<Runnable> ready = new ArrayList<>(pending);
        pending.clear();
        for (Runnable work : ready) {
            if (!destroyed) work.run();
        }
    };

    public ScrollIdleGate(@NonNull View host) {
        this.host = host;
    }

    public void onTouchEvent(@NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                lastMotionUptime = SystemClock.uptimeMillis();
                markScrolling();
                cancelIdleCheck();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                lastMotionUptime = SystemClock.uptimeMillis();
                scheduleIdleCheck(IDLE_DELAY_MS);
                break;
            default:
                break;
        }
    }

    public void onScrollChanged() {
        lastMotionUptime = SystemClock.uptimeMillis();
        markScrolling();
        if (!touching) scheduleIdleCheck(IDLE_DELAY_MS);
    }

    public boolean isScrolling() {
        return scrolling || touching;
    }

    public void runWhenIdle(@NonNull Runnable work) {
        if (destroyed) return;
        if (!isScrolling()) {
            work.run();
            return;
        }
        pending.add(work);
    }

    public void cancel(@NonNull Runnable work) {
        pending.remove(work);
    }

    public void addScrollStartedListener(@NonNull Runnable listener) {
        if (!scrollStartedListeners.contains(listener)) scrollStartedListeners.add(listener);
    }

    public void removeScrollStartedListener(@NonNull Runnable listener) {
        scrollStartedListeners.remove(listener);
    }

    public void destroy() {
        destroyed = true;
        cancelIdleCheck();
        pending.clear();
        scrollStartedListeners.clear();
    }

    private void markScrolling() {
        if (destroyed || scrolling) return;
        scrolling = true;
        List<Runnable> listeners = new ArrayList<>(scrollStartedListeners);
        for (Runnable listener : listeners) listener.run();
    }

    private void scheduleIdleCheck(long delayMs) {
        if (destroyed || settleScheduled) return;
        settleScheduled = true;
        host.postDelayed(settleRunnable, Math.max(1L, delayMs));
    }

    private void cancelIdleCheck() {
        if (!settleScheduled) return;
        host.removeCallbacks(settleRunnable);
        settleScheduled = false;
    }
}
