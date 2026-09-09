package fr.neamar.kiss.ui;

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

    private final Runnable settleRunnable = () -> {
        if (destroyed || touching) return;
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
                markScrolling();
                host.removeCallbacks(settleRunnable);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                scheduleIdleCheck();
                break;
            default:
                break;
        }
    }

    public void onScrollChanged() {
        markScrolling();
        if (!touching) scheduleIdleCheck();
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
        host.removeCallbacks(settleRunnable);
        pending.clear();
        scrollStartedListeners.clear();
    }

    private void markScrolling() {
        if (destroyed || scrolling) return;
        scrolling = true;
        List<Runnable> listeners = new ArrayList<>(scrollStartedListeners);
        for (Runnable listener : listeners) listener.run();
    }

    private void scheduleIdleCheck() {
        if (destroyed) return;
        host.removeCallbacks(settleRunnable);
        host.postDelayed(settleRunnable, IDLE_DELAY_MS);
    }
}
