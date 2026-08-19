package fr.neamar.kiss.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/** Caps app-supplied notification RemoteViews so they cannot distort Smart S dialogs. */
public class CompactNotificationFrame extends FrameLayout {
    private int maxHeightPx = Integer.MAX_VALUE;
    private boolean interceptChildTouches;

    public CompactNotificationFrame(Context context) { super(context); }
    public CompactNotificationFrame(Context context, AttributeSet attrs) { super(context, attrs); }

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
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return interceptChildTouches || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!interceptChildTouches) return super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return true;
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
