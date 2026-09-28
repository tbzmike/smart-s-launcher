package fr.neamar.kiss.ui;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AbsListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps optional launcher work out of an active scroll gesture/fling.
 *
 * Native Vertical List uses ListView's own TOUCH_SCROLL / FLING / IDLE callbacks and therefore
 * does no periodic main-looper polling while a fling is active. The optional View constructor is
 * retained only for the custom ScrollView renderers, which do not expose AbsListView state.
 */
public final class ScrollIdleGate {
    private static final long LEGACY_IDLE_DELAY_MS = 180L;

    @Nullable private final View legacyHost;
    private final Set<Runnable> pending = new LinkedHashSet<>();
    private final List<Runnable> scrollStartedListeners = new ArrayList<>();

    private boolean touching;
    private boolean scrolling;
    private boolean destroyed;
    private int nativeScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;

    // Compatibility state for wheel/card ScrollView users only. Native Vertical List never uses it.
    private boolean legacySettleScheduled;
    private long legacyLastMotionUptime;

    private final Runnable legacySettleRunnable = () -> {
        legacySettleScheduled = false;
        if (destroyed || legacyHost == null || touching) return;

        long elapsed = Math.max(0L, SystemClock.uptimeMillis() - legacyLastMotionUptime);
        if (elapsed < LEGACY_IDLE_DELAY_MS) {
            scheduleLegacyIdleCheck(LEGACY_IDLE_DELAY_MS - elapsed);
            return;
        }
        finishIdle();
    };

    /** Native ListView mode: no polling timer. */
    public ScrollIdleGate() {
        legacyHost = null;
    }

    /** Compatibility mode for custom ScrollViews that lack native scroll-state callbacks. */
    public ScrollIdleGate(@NonNull View host) {
        legacyHost = host;
    }

    public void onTouchEvent(@NonNull MotionEvent event) {
        if (destroyed) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                markScrolling();
                if (legacyHost != null) {
                    legacyLastMotionUptime = SystemClock.uptimeMillis();
                    cancelLegacyIdleCheck();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                if (legacyHost != null) {
                    legacyLastMotionUptime = SystemClock.uptimeMillis();
                    scheduleLegacyIdleCheck(LEGACY_IDLE_DELAY_MS);
                } else if (nativeScrollState
                        == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                    finishIdle();
                }
                break;
            default:
                break;
        }
    }

    /** Called only by native ListView mode. */
    public void onScrollStateChanged(int scrollState) {
        if (destroyed || legacyHost != null) return;
        nativeScrollState = scrollState;
        if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
            if (!touching) finishIdle();
        } else {
            markScrolling();
        }
    }

    /**
     * Compatibility hook for wheel/card ScrollViews. AnimatedListView deliberately never calls it.
     */
    public void onScrollChanged() {
        if (destroyed || legacyHost == null) return;
        legacyLastMotionUptime = SystemClock.uptimeMillis();
        markScrolling();
        if (!touching) scheduleLegacyIdleCheck(LEGACY_IDLE_DELAY_MS);
    }

    public boolean isScrolling() {
        if (legacyHost != null) return scrolling || touching;
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
        cancelLegacyIdleCheck();
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

    private void scheduleLegacyIdleCheck(long delayMs) {
        if (destroyed || legacyHost == null || legacySettleScheduled) return;
        legacySettleScheduled = true;
        legacyHost.postDelayed(legacySettleRunnable, Math.max(1L, delayMs));
    }

    private void cancelLegacyIdleCheck() {
        if (!legacySettleScheduled || legacyHost == null) return;
        legacyHost.removeCallbacks(legacySettleRunnable);
        legacySettleScheduled = false;
    }
}
