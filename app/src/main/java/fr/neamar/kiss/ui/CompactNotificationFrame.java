package fr.neamar.kiss.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Caps app-supplied notification RemoteViews so they cannot distort Smart S dialogs. */
public class CompactNotificationFrame extends FrameLayout {
    private int maxHeightPx = Integer.MAX_VALUE;

    public CompactNotificationFrame(Context context) { super(context); }
    public CompactNotificationFrame(Context context, AttributeSet attrs) { super(context, attrs); }

    public void setMaxHeightDp(int dp) {
        maxHeightPx = Math.round(dp * getResources().getDisplayMetrics().density);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
        super.onMeasure(widthMeasureSpec, capped);
    }
}
