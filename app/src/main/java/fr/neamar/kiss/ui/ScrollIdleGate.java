package fr.neamar.kiss.ui;

import android.view.MotionEvent;
import android.widget.AbsListView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps optional launcher work out of an active ListView gesture/fling.
 *
 * Older builds used a delayed 180 ms settle Runnable that woke repeatedly on the main looper
 * throughout a long fling. That repeating wake-up was itself unnecessary work in the scroll
 * critical path. The gate now follows ListView's native scroll-state callbacks and performs no
 * periodic polling while the list is moving.
 */
public final class ScrollIdleGate {
    private final Set<Runnable> pending = new LinkedHashSet<>();
    private final List<Runnable> scrollStartedListeners = new ArrayList<>();

    private boolean touching;
    private boolean scrolling;
    private boolean destroyed;
    private int nativeScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;

    public ScrollIdleGate() {
    }

    public void onTouchEvent(@NonNull MotionEvent event) {
        if (destroyed) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                // Enter the hard work gate immediately, before ListView has decided whether this
                // touch becomes a drag. A simple tap exits again on ACTION_UP below.
                markScrolling();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                if (nativeScrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                    finishIdle();
                }
                break;
            default:
                break;
        }
    }

    public void onScrollStateChanged(int scrollState) {
        if (destroyed) return;
        nativeScrollState = scrollState;
        if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
            if (!touching) finishIdle();
        } else {
            markScrolling();
        }
    }

    public boolean isScrolling() {
        return scrolling || touching
                || nativeScrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
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
        pending.clear();
        scrollStartedListeners.clear();
        touching = false;
        scrolling = false;
        nativeScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
    }

    private void markScrolling() {
        if (destroyed || scrolling) return;
        scrolling = true;
        List<Runnable> listeners = new ArrayList<>(scrollStartedListeners);
        for (Runnable listener : listeners) listener.run();
    }

    private void finishIdle() {
        if (destroyed) return;
        scrolling = false;
        if (pending.isEmpty()) return;

        List<Runnable> ready = new ArrayList<>(pending);
        pending.clear();
        for (Runnable work : ready) {
            if (!destroyed) work.run();
        }
    }
}
