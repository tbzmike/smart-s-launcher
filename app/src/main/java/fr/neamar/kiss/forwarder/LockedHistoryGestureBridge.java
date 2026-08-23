package fr.neamar.kiss.forwarder;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ScrollView;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.preference.UiEditLock;

/**
 * Feeds horizontal gestures performed over the standard Vertical list and Vertical Cards back into
 * KISS' existing ExperienceTweaks gesture detector while UI editing is locked.
 *
 * The bridge never consumes the original event stream. Vertical scrolling, taps, long presses and
 * notification controls therefore remain owned by their normal ListView/ScrollView/card handlers.
 */
final class LockedHistoryGestureBridge extends Forwarder {
    private final ExperienceTweaks experienceTweaks;
    private final int touchSlop;

    private MotionEvent pendingDown;
    private float downRawX;
    private float downRawY;
    private boolean horizontalConfirmed;
    private boolean verticalRejected;

    private final View.OnTouchListener passthrough = (view, event) -> {
        observe(event);
        return false;
    };

    LockedHistoryGestureBridge(MainActivity mainActivity, ExperienceTweaks experienceTweaks) {
        super(mainActivity);
        this.experienceTweaks = experienceTweaks;
        this.touchSlop = ViewConfiguration.get(mainActivity).getScaledTouchSlop();
    }

    void onCreate() {
        attach();
    }

    void onResume() {
        attach();
        resetGesture();
    }

    void onDataSetChanged() {
        attach();
    }

    void onPause() {
        resetGesture();
    }

    void onDestroy() {
        resetGesture();
        if (mainActivity.list != null) mainActivity.list.setOnTouchListener(null);
    }

    private boolean isSupportedLockedMode() {
        if (!UiEditLock.isLocked(mainActivity)) return false;
        String mode = prefs.getString(HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL);
        return HistoryDisplayForwarder.VERTICAL.equals(mode)
                || HistoryDisplayForwarder.VERTICAL_CARDS.equals(mode);
    }

    private void attach() {
        if (mainActivity.list != null) mainActivity.list.setOnTouchListener(passthrough);

        if (!(mainActivity.listContainer instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) mainActivity.listContainer;
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if (!(child instanceof ScrollView)) continue;
            // SmartCardListForwarder's Vertical Cards scroller is the direct ScrollView child of
            // resultLayout. Square-U's notification scroller is nested inside squareRoot instead.
            child.setOnTouchListener(passthrough);
            attachToClickableDescendants((ViewGroup) child);
        }
    }

    private void attachToClickableDescendants(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.isClickable() || child.isLongClickable()) child.setOnTouchListener(passthrough);
            if (child instanceof ViewGroup) attachToClickableDescendants((ViewGroup) child);
        }
    }

    private void observe(MotionEvent event) {
        if (event == null || !isSupportedLockedMode()) {
            resetGesture();
            return;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resetGesture();
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                pendingDown = normalizedCopy(event);
                break;

            case MotionEvent.ACTION_MOVE:
                if (pendingDown == null || verticalRejected) break;
                if (!horizontalConfirmed) {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    float absX = Math.abs(dx);
                    float absY = Math.abs(dy);
                    if (absX > touchSlop && absX > absY * 1.15f) {
                        horizontalConfirmed = true;
                        // The existing KISS GestureDetector needs the original DOWN before MOVE/UP.
                        experienceTweaks.onTouch(pendingDown);
                    } else if (absY > touchSlop && absY > absX) {
                        // Never feed vertical list scrolling into KISS' up/down launcher gestures.
                        verticalRejected = true;
                    }
                }
                if (horizontalConfirmed) forwardNormalized(event);
                break;

            case MotionEvent.ACTION_UP:
                if (horizontalConfirmed) forwardNormalized(event);
                resetGesture();
                break;

            case MotionEvent.ACTION_CANCEL:
                if (horizontalConfirmed) forwardNormalized(event);
                resetGesture();
                break;

            default:
                if (horizontalConfirmed) forwardNormalized(event);
                break;
        }
    }

    private MotionEvent normalizedCopy(MotionEvent event) {
        MotionEvent copy = MotionEvent.obtain(event);
        copy.setLocation(event.getRawX(), event.getRawY());
        return copy;
    }

    private void forwardNormalized(MotionEvent event) {
        MotionEvent copy = normalizedCopy(event);
        experienceTweaks.onTouch(copy);
        copy.recycle();
    }

    private void resetGesture() {
        if (pendingDown != null) {
            pendingDown.recycle();
            pendingDown = null;
        }
        downRawX = 0f;
        downRawY = 0f;
        horizontalConfirmed = false;
        verticalRejected = false;
    }
}
