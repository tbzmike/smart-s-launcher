package fr.neamar.kiss.ui;

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.Collections;

/**
 * Source: <a href="https://github.com/willli666/Android-Trebuchet-Launcher-Standalone/blob/master/src/com/cyanogenmod/trebuchet/LauncherAppWidgetHostView.java">LauncherAppWidgetHostView.java</a>
 */
public class WidgetView extends AppWidgetHostView {
    protected boolean mHasPerformedLongPress;
    private CheckForLongPress mPendingCheckForLongPress;
    private float xPos;
    private float yPos;
    private boolean protectScrollableGesture;

    public WidgetView(Context context) {
        super(context);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Collection widgets (calendar, mail, feeds, lists, etc.) must own their
                // scrolling gesture.  Without this, a freeform/stack parent can steal MOVE
                // events before the widget's ListView/RecyclerView gets a chance to scroll.
                protectScrollableGesture = hasScrollableDescendant(this);
                if (protectScrollableGesture && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (protectScrollableGesture && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean handled = super.dispatchTouchEvent(ev);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                protectScrollableGesture = false;
                return handled;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean hasScrollableDescendant(View view) {
        if (view != this && (view.canScrollVertically(-1) || view.canScrollVertically(1)
                || view.canScrollHorizontally(-1) || view.canScrollHorizontally(1))) {
            return true;
        }
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && hasScrollableDescendant(child)) return true;
        }
        return false;
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Consume any touch events for ourselves after long press is triggered
        if (mHasPerformedLongPress) {
            mHasPerformedLongPress = false;
            return true;
        }

        // Watch for long press events at this level to make sure
        // users can always pick up this widget
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                postCheckForLongClick();
                xPos = ev.getX();
                yPos = ev.getY();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (Math.abs(ev.getX() - xPos) > 5 || Math.abs(ev.getY() - yPos) > 5) {
                    mHasPerformedLongPress = false;
                    if (mPendingCheckForLongPress != null) {
                        removeCallbacks(mPendingCheckForLongPress);
                    }
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mHasPerformedLongPress = false;
                if (mPendingCheckForLongPress != null) {
                    removeCallbacks(mPendingCheckForLongPress);
                }
                break;
        }

        // Otherwise continue letting touch events fall through to children
        return false;
    }

    protected class CheckForLongPress implements Runnable {
        private int mOriginalWindowAttachCount;

        public void run() {
            if ((getParent() != null) && hasWindowFocus()
                    && mOriginalWindowAttachCount == getWindowAttachCount()
                    && !WidgetView.this.mHasPerformedLongPress) {
                if (performLongClick()) {
                    WidgetView.this.mHasPerformedLongPress = true;
                }
            }
        }

        void rememberWindowAttachCount() {
            mOriginalWindowAttachCount = getWindowAttachCount();
        }
    }

    private void postCheckForLongClick() {
        mHasPerformedLongPress = false;

        if (mPendingCheckForLongPress == null) {
            mPendingCheckForLongPress = new CheckForLongPress();
        }
        mPendingCheckForLongPress.rememberWindowAttachCount();
        postDelayed(mPendingCheckForLongPress, ViewConfiguration.getLongPressTimeout());
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();

        mHasPerformedLongPress = false;
        if (mPendingCheckForLongPress != null) {
            removeCallbacks(mPendingCheckForLongPress);
        }
    }

    @Override
    public int getDescendantFocusability() {
        return ViewGroup.FOCUS_BLOCK_DESCENDANTS;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // calculate size in dips
        float density = getResources().getDisplayMetrics().density;
        int widthDips = (int) (w / density);
        int heightDips = (int) (h / density);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updateAppWidgetSize(Bundle.EMPTY, Collections.singletonList(new SizeF(widthDips, heightDips)));
        } else {
            updateAppWidgetSize(null, widthDips, heightDips, widthDips, heightDips);
        }
    }
}
