package fr.neamar.kiss.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Caps app-supplied notification RemoteViews so they cannot distort Smart S dialogs. */
public class CompactNotificationFrame extends FrameLayout {
    private int maxHeightPx = Integer.MAX_VALUE;
    private boolean interceptChildTouches;
    private boolean longPressTriggered;
    private float downX;
    private float downY;
    private final int touchSlop;
    private final Runnable longPressRunnable = () -> {
        if (!interceptChildTouches || !isPressed()) return;
        longPressTriggered = performLongClick();
    };

    public CompactNotificationFrame(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public CompactNotificationFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setMaxHeightDp(int dp) {
        maxHeightPx = Math.round(dp * getResources().getDisplayMetrics().density);
        requestLayout();
    }

    /**
     * When enabled, Smart S owns taps on this frame instead of allowing app-supplied
     * RemoteViews PendingIntents to consume them. This keeps native notification styling
     * while preserving the launcher's popup interaction model.
     */
    public void setInterceptChildTouches(boolean intercept) {
        interceptChildTouches = intercept;
        setClickable(intercept || isClickable());
        if (!intercept) cancelLongPressTracking();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return interceptChildTouches || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!interceptChildTouches) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                longPressTriggered = false;
                setPressed(true);
                removeCallbacks(longPressRunnable);
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > touchSlop
                        || Math.abs(event.getY() - downY) > touchSlop) {
                    removeCallbacks(longPressRunnable);
                    setPressed(false);
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPressRunnable);
                boolean wasLongPress = longPressTriggered;
                setPressed(false);
                if (!wasLongPress) performClick();
                longPressTriggered = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelLongPressTracking();
                return true;
            default:
                return true;
        }
    }

    private void cancelLongPressTracking() {
        removeCallbacks(longPressRunnable);
        setPressed(false);
        longPressTriggered = false;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelLongPressTracking();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
        super.onMeasure(widthMeasureSpec, capped);
    }
}
