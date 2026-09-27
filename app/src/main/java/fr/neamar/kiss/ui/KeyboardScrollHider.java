package fr.neamar.kiss.ui;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;

import androidx.annotation.NonNull;

/**
 * Utility class for automatically hiding the system keyboard when scrolling down a
 * {@link android.widget.ListView}, keeping the position of the finger on the list stable.
 * Launcher-owned keyboards can opt out of scroll-dismissal through the handler.
 */
public class KeyboardScrollHider implements View.OnTouchListener {
    private final static int THRESHOLD = 24;

    private final KeyboardHandler handler;
    protected final BlockableListView list;
    private final View listParent;
    protected final BottomPullEffectView pullEffect;
    private int listHeightInitial = 0;

    private float offsetYStart = 0;
    private float offsetYCurrent = 0;
    private int offsetYDiff = 0;

    private MotionEvent lastMotionEvent;
    private int initialWindowPadding = 0;
    private boolean resizeDone = false;
    private boolean resizeActive = false;

    private boolean scrollBarEnabled = true;

    public KeyboardScrollHider(KeyboardHandler handler, BlockableListView list, BottomPullEffectView pullEffect) {
        this.handler = handler;
        this.list = list;
        this.listParent = (View) list.getParent();
        this.pullEffect = pullEffect;
    }

    public void start() {
        this.list.setOnTouchListener(this);
    }

    public void stop() {
        this.list.setOnTouchListener(null);
        this.handleResizeDone();
    }

    private int getWindowPadding() {
        ViewGroup rootView = (ViewGroup) this.list.getRootView();
        return rootView.getChildAt(0).getPaddingBottom();
    }

    private int getWindowWidth() {
        ViewGroup rootView = (ViewGroup) this.list.getRootView();
        return rootView.getChildAt(0).getWidth();
    }

    private void setListLayoutHeight(int height) {
        final ViewGroup.LayoutParams params = this.list.getLayoutParams();
        if (params.height == height) return;
        params.height = height;
        // setLayoutParams already requests the necessary layout. forceLayout() here used to force
        // an additional measure/layout pass for every touch/animation frame.
        this.list.setLayoutParams(params);
    }

    protected void handleResizeDone() {
        if (this.resizeDone) return;

        this.list.unblockTouchEvents();
        this.pullEffect.releasePull();
        this.list.setVerticalScrollBarEnabled(this.scrollBarEnabled);
        if (this.resizeActive) {
            this.setListLayoutHeight(ViewGroup.LayoutParams.MATCH_PARENT);
        }
        this.resizeActive = false;
        this.resizeDone = true;
    }

    private void updateListViewHeight() {
        if (this.getWindowPadding() >= this.initialWindowPadding || this.resizeDone) {
            return;
        }

        this.resizeActive = true;
        this.list.blockTouchEvents();
        this.list.setVerticalScrollBarEnabled(false);

        int heightContainer = this.listParent.getHeight();
        int offsetYDiff = (int) (this.offsetYCurrent - this.offsetYStart);
        if (offsetYDiff < (this.offsetYDiff - THRESHOLD)) {
            double pullFeedback = Math.sqrt((double) (this.offsetYDiff - offsetYDiff) / THRESHOLD);
            offsetYDiff = this.offsetYDiff - (int) (THRESHOLD * pullFeedback);
        }

        int listLayoutHeight = ViewGroup.LayoutParams.MATCH_PARENT;
        if ((this.listHeightInitial + offsetYDiff) < heightContainer) {
            listLayoutHeight = this.listHeightInitial + offsetYDiff;
        }
        this.setListLayoutHeight(listLayoutHeight);
        if (offsetYDiff > this.offsetYDiff) {
            this.offsetYDiff = offsetYDiff;
        }

        if (this.getWindowPadding() < this.initialWindowPadding
                && listLayoutHeight == ViewGroup.LayoutParams.MATCH_PARENT) {
            this.handleResizeDone();
            return;
        }

        float distance = ((float) (heightContainer - listLayoutHeight)) / heightContainer;
        float displacement = 1 - this.lastMotionEvent.getX() / getWindowWidth();
        this.pullEffect.setPull(distance, displacement, false);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        this.scrollBarEnabled = this.list.isVerticalScrollBarEnabled();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                this.offsetYStart = event.getY();
                this.offsetYCurrent = event.getY();
                this.offsetYDiff = 0;
                this.lastMotionEvent = event;
                this.resizeDone = false;
                this.resizeActive = false;
                this.initialWindowPadding = this.getWindowPadding();
                this.listHeightInitial = this.list.getHeight();
                // Do not force an otherwise unchanged ListView through measure/layout at the start
                // of every scroll gesture. Resize only if the system IME inset actually changes.
                break;

            case MotionEvent.ACTION_MOVE:
                this.offsetYCurrent = event.getY();
                this.lastMotionEvent = event;
                this.updateListViewHeight();
                break;

            case MotionEvent.ACTION_UP:
                this.lastMotionEvent = null;
                if (Math.abs(this.offsetYCurrent - this.offsetYStart) <= THRESHOLD) {
                    this.handleResizeDone();
                    break;
                }
                // fall through for a real scroll gesture
            case MotionEvent.ACTION_CANCEL:
                this.lastMotionEvent = null;

                if (isScrolled()) {
                    this.handler.hideKeyboardOnScroll();
                }

                // If the IME inset never changed, there is nothing to resize. The old code still
                // ran a ValueAnimator from height X to the same height X and force-laid out the
                // ListView on every animation frame after every fling.
                if (!this.resizeActive || this.resizeDone) {
                    this.handleResizeDone();
                    break;
                }

                ValueAnimator animator = ValueAnimator.ofInt(
                        this.list.getHeight(),
                        this.listParent.getHeight()
                );
                int animationDuration = v.getContext().getResources().getInteger(android.R.integer.config_shortAnimTime);
                animator.setDuration(animationDuration);
                animator.setInterpolator(new AccelerateInterpolator());
                animator.addUpdateListener(animation -> {
                    int height = (int) animation.getAnimatedValue();
                    KeyboardScrollHider.this.setListLayoutHeight(height);
                });
                animator.addListener(new Animator.AnimatorListener() {
                    @Override
                    public void onAnimationStart(@NonNull Animator animation) {
                        KeyboardScrollHider.this.list.unblockTouchEvents();
                        KeyboardScrollHider.this.pullEffect.releasePull();
                    }

                    @Override
                    public void onAnimationEnd(@NonNull Animator animation) {
                        KeyboardScrollHider.this.handleResizeDone();
                    }

                    @Override
                    public void onAnimationCancel(@NonNull Animator animation) {
                        KeyboardScrollHider.this.handleResizeDone();
                    }

                    @Override
                    public void onAnimationRepeat(@NonNull Animator animation) {
                    }
                });
                animator.start();
                break;
        }

        if (isScrolled()) {
            this.handler.applyScrollSystemUi();
        }

        return false;
    }

    public void fixScroll() {
        this.list.post(() -> {
            resizeDone = false;
            handleResizeDone();
        });
    }

    public boolean isScrolled() {
        return (this.offsetYCurrent - this.offsetYStart) > THRESHOLD;
    }

    public interface KeyboardHandler {
        void showKeyboard();

        void hideKeyboard();

        void hideKeyboardOnScroll();

        void applyScrollSystemUi();
    }
}
