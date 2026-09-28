package fr.neamar.kiss.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;

import java.util.HashMap;

public class AnimatedListView extends BlockableListView {

    protected final HashMap<Long, ItemInfo> mItemMap = new HashMap<>();
    private ScrollIdleGate scrollIdleGate;
    private AbsListView.OnScrollListener externalScrollListener;
    private final AbsListView.OnScrollListener internalScrollListener =
            new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    if (scrollIdleGate != null) scrollIdleGate.onScrollStateChanged(scrollState);
                    if (externalScrollListener != null) {
                        externalScrollListener.onScrollStateChanged(view, scrollState);
                    }
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem,
                                     int visibleItemCount, int totalItemCount) {
                    if (externalScrollListener != null) {
                        externalScrollListener.onScroll(
                                view, firstVisibleItem, visibleItemCount, totalItemCount);
                    }
                }
            };
    private ViewTreeObserver pendingAnimationObserver;
    private ViewTreeObserver.OnPreDrawListener pendingAnimationListener;
    public AnimatedListView(Context context) {
        super(context);
        initScrollIdleGate();
    }

    public AnimatedListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initScrollIdleGate();
    }

    public AnimatedListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initScrollIdleGate();
    }

    private void initScrollIdleGate() {
        scrollIdleGate = new ScrollIdleGate();
        // Use ListView's own TOUCH_SCROLL / FLING / IDLE state instead of a repeating delayed
        // polling Runnable. Keep this internal listener installed even if another feature later
        // asks for scroll callbacks.
        super.setOnScrollListener(internalScrollListener);
    }

    @Override
    public void setOnScrollListener(AbsListView.OnScrollListener listener) {
        if (listener == internalScrollListener) {
            super.setOnScrollListener(listener);
            return;
        }
        externalScrollListener = listener;
        super.setOnScrollListener(internalScrollListener);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (scrollIdleGate != null) scrollIdleGate.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        // Deliberately no scroll-idle polling here. Native scroll state drives ScrollIdleGate.
        super.onScrollChanged(l, t, oldl, oldt);
    }

    public boolean isScrollInProgress() {
        return scrollIdleGate != null && scrollIdleGate.isScrolling();
    }

    @SuppressLint("WrongThreadInterprocedural")
    public void runWhenScrollIdle(Runnable work) {
        if (scrollIdleGate == null) work.run();
        else scrollIdleGate.runWhenIdle(work);
    }

    public void cancelWhenScrollIdle(Runnable work) {
        if (scrollIdleGate != null) scrollIdleGate.cancel(work);
    }

    public void addScrollStartedListener(Runnable listener) {
        if (scrollIdleGate != null) scrollIdleGate.addScrollStartedListener(listener);
    }

    public void removeScrollStartedListener(Runnable listener) {
        if (scrollIdleGate != null) scrollIdleGate.removeScrollStartedListener(listener);
    }

    public void prepareChangeAnim() {
        cancelPendingChangeAnimation();
        mItemMap.clear();

        int firstVisiblePosition = this.getFirstVisiblePosition();
        int nCount = Math.min(this.getChildCount(), getAdapter().getCount() - firstVisiblePosition);
        for (int i = 0; i < nCount; i += 1) {
            View child = this.getChildAt(i);
            child.clearAnimation();
            int position = firstVisiblePosition + i;
            long itemId = getAdapter().getItemId(position);
            mItemMap.put(itemId, new ItemInfo(i, child.getTop()));
        }
    }

    public void animateChange() {
        if (mItemMap.isEmpty()) return;

        cancelPendingChangeAnimation();

        final ViewTreeObserver observer = this.getViewTreeObserver();
        if (!observer.isAlive()) return;

        pendingAnimationObserver = observer;
        pendingAnimationListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                if (pendingAnimationListener == this) {
                    pendingAnimationListener = null;
                    pendingAnimationObserver = null;
                }

                AnimatedListView listView = AnimatedListView.this;
                int firstVisiblePosition = listView.getFirstVisiblePosition();
                int nCount = Math.min(listView.getChildCount(), getAdapter().getCount() - firstVisiblePosition);
                for (int i = 0; i < nCount; i += 1) {
                    int position = firstVisiblePosition + i;
                    long itemId = getAdapter().getItemId(position);
                    View child = listView.getChildAt(i);
                    ItemInfo itemInfo = mItemMap.get(itemId);
                    int delta;
                    boolean isNew = itemInfo == null;

                    if (!isNew) {
                        delta = itemInfo.top - child.getTop();
                    } else if (i == 0) {
                        delta = -child.getHeight() - listView.getDividerHeight();
                    } else {
                        delta = child.getHeight() + listView.getDividerHeight();
                    }

                    SmartAnimationEngine.animateListMove(child, delta, isNew);
                }

                return false;
            }
        };
        observer.addOnPreDrawListener(pendingAnimationListener);
    }

    private void cancelPendingChangeAnimation() {
        if (pendingAnimationObserver != null && pendingAnimationListener != null
                && pendingAnimationObserver.isAlive()) {
            pendingAnimationObserver.removeOnPreDrawListener(pendingAnimationListener);
        }
        pendingAnimationObserver = null;
        pendingAnimationListener = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelPendingChangeAnimation();
        if (scrollIdleGate != null) {
            scrollIdleGate.destroy();
        }
        super.onDetachedFromWindow();
    }

    protected static class ItemInfo {
        final int top;
        final int viewIndex;

        ItemInfo(int viewIndex, int top) {
            this.viewIndex = viewIndex;
            this.top = top;
        }
    }
}
