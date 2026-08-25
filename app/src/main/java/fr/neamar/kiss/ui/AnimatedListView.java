package fr.neamar.kiss.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.HashMap;

public class AnimatedListView extends BlockableListView {

    protected final HashMap<Long, ItemInfo> mItemMap = new HashMap<>();
    private SmartScrollAnimationController smartScrollAnimations;
    private ViewTreeObserver pendingAnimationObserver;
    private ViewTreeObserver.OnPreDrawListener pendingAnimationListener;

    public AnimatedListView(Context context) {
        super(context);
        initSmartAnimations();
    }

    public AnimatedListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initSmartAnimations();
    }

    public AnimatedListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initSmartAnimations();
    }

    private void initSmartAnimations() {
        smartScrollAnimations = new SmartScrollAnimationController(this);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (smartScrollAnimations != null) smartScrollAnimations.apply();
    }

    public void prepareChangeAnim() {
        cancelPendingChangeAnimation();
        mItemMap.clear();

        // store positions before the update
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
        if (mItemMap.isEmpty())
            return;

        // Only one pre-draw animation may own a list update. Search can replace results more than
        // once before the next frame; stacking listeners here made the same child receive several
        // conflicting translations/scales and produced the visible vertical-card jump.
        cancelPendingChangeAnimation();

        final ViewTreeObserver observer = this.getViewTreeObserver();
        if (!observer.isAlive())
            return;

        int animationDuration = getContext().getResources().getInteger(android.R.integer.config_shortAnimTime);

        pendingAnimationObserver = observer;
        pendingAnimationListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                }
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
                    int delta = 0;
                    ItemInfo itemInfo = mItemMap.get(itemId);

                    if (itemInfo != null) {
                        int topBeforeLayout = itemInfo.top;
                        int topAfterLayout = child.getTop();
                        delta = topBeforeLayout - topAfterLayout;
                    } else {
                        if (i == 0) {
                            delta = -child.getHeight() - listView.getDividerHeight();
                        } else {
                            child.setScaleY(0.f);
                            child.animate()
                                    .setDuration(animationDuration)
                                    .scaleY(1.f);
                        }
                    }
                    if (delta != 0) {
                        child.setTranslationY(delta);
                        child.animate()
                                .setDuration(animationDuration)
                                .translationY(0);
                    }
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
