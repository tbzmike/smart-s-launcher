package fr.neamar.kiss.forwarder;

import android.animation.ValueAnimator;
import android.app.WallpaperManager;
import android.content.res.TypedArray;
import android.graphics.Point;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

import androidx.annotation.StyleableRes;
import androidx.core.content.ContextCompat;

import fr.neamar.kiss.MainActivity;

class LiveWallpaper extends Forwarder {

    private final boolean wallpaperIsVisible;

    private WallpaperManager mWallpaperManager;
    protected Point mWindowSize;
    private android.os.IBinder mWindowToken;
    private View mContentView;
    private float mLastTouchPos;
    private float mWallpaperOffset;
    private ValueAnimator mWallpaperAnimator;
    private VelocityTracker mVelocityTracker;

    LiveWallpaper(MainActivity mainActivity) {
        super(mainActivity);
        @StyleableRes int[] attrs = new int[]{android.R.attr.windowShowWallpaper};
        try (TypedArray a = mainActivity.obtainStyledAttributes(attrs)) {
            wallpaperIsVisible = a.getBoolean(0, true);
        }

        if (!wallpaperIsVisible) {
            return;
        }

        mWallpaperManager = ContextCompat.getSystemService(mainActivity, WallpaperManager.class);
        assert mWallpaperManager != null;

        mContentView = mainActivity.findViewById(android.R.id.content);
        mWallpaperManager.setWallpaperOffsetSteps(.5f, 0.f);
        mWallpaperOffset = 0.5f; // this is the center
        mWallpaperAnimator = null;
        mVelocityTracker = null;
        mWindowSize = new Point(1, 1);
    }

    boolean onTouch(View view, MotionEvent event) {
        if (!wallpaperIsVisible) {
            return false;
        }

        int actionMasked = event.getActionMasked();
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
                if (isPreferenceWPDragAnimate()) {
                    cancelWallpaperAnimation();

                    mVelocityTracker = VelocityTracker.obtain();
                    mVelocityTracker.addMovement(event);

                    mLastTouchPos = event.getRawX();
                    mainActivity.getWindowManager()
                            .getDefaultDisplay()
                            .getSize(mWindowSize);
                }
                //send touch event to the LWP
                if (isPreferenceLWPTouch())
                    sendTouchEvent(view, event);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mVelocityTracker != null) {
                    mVelocityTracker.addMovement(event);

                    float fTouchPos = event.getRawX();
                    float fOffset = (mLastTouchPos - fTouchPos) * 1.01f / mWindowSize.x;
                    fOffset += mWallpaperOffset;
                    updateWallpaperOffset(fOffset);
                    mLastTouchPos = fTouchPos;
                }

                //send move/drag event to the LWP
                if (isPreferenceLWPDrag())
                    sendTouchEvent(view, event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mVelocityTracker != null) {
                    mVelocityTracker.addMovement(event);

                    mVelocityTracker.computeCurrentVelocity(1000 / 30);
                    startWallpaperAnimation(
                            isPreferenceWPStickToSides(),
                            isPreferenceWPReturnCenter(),
                            mVelocityTracker.getXVelocity(),
                            mWallpaperOffset
                    );

                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
        }

        // do not consume the event
        return false;
    }

    private boolean isPreferenceLWPTouch() {
        return prefs.getBoolean("lwp-touch", true);
    }

    private boolean isPreferenceLWPDrag() {
        return prefs.getBoolean("lwp-drag", false);
    }

    private boolean isPreferenceWPDragAnimate() {
        return prefs.getBoolean("wp-drag-animate", false);
    }

    private boolean isPreferenceWPReturnCenter() {
        return prefs.getBoolean("wp-animate-center", true);
    }

    private boolean isPreferenceWPStickToSides() {
        return prefs.getBoolean("wp-animate-sides", false);
    }

    private android.os.IBinder getWindowToken() {
        return mWindowToken != null ? mWindowToken : (mWindowToken = mContentView.getWindowToken());
    }

    protected void updateWallpaperOffset(float offset) {
        android.os.IBinder iBinder = getWindowToken();
        if (iBinder != null) {
            offset = Math.max(0.f, Math.min(1.f, offset));
            mWallpaperOffset = offset;
            mWallpaperManager.setWallpaperOffsets(iBinder, mWallpaperOffset, 0.f);
        }
    }

    private void cancelWallpaperAnimation() {
        if (mWallpaperAnimator != null) {
            mWallpaperAnimator.cancel();
            mWallpaperAnimator = null;
        }
    }

    private void startWallpaperAnimation(boolean stickToSides, boolean stickToCenter, float velocity, float wallpaperOffset) {
        float startOffset = wallpaperOffset;
        float expectedPos = -Math.min(Math.max(velocity / mWindowSize.x, -.5f), .5f) + startOffset;

        // if we stick only to the center
        float leftStickPercent = -1.f;
        float rightStickPercent = 2.f;

        if (stickToSides && stickToCenter) {
            // if we stick to the left, right and center
            leftStickPercent = .2f;
            rightStickPercent = .8f;
        } else if (stickToSides) {
            // if we stick only to the sides
            leftStickPercent = .5f;
            rightStickPercent = .5f;
        }

        final float deltaOffset;
        if (expectedPos <= leftStickPercent)
            deltaOffset = 0.f - startOffset;
        else if (expectedPos >= rightStickPercent)
            deltaOffset = 1.f - startOffset;
        else if (stickToCenter)
            deltaOffset = .5f - startOffset;
        else
            return;

        cancelWallpaperAnimation();
        ValueAnimator animator = ValueAnimator.ofFloat(0.f, 1.f);
        animator.setDuration(1000);
        animator.addUpdateListener(valueAnimator -> {
            float interpolatedTime = (float) valueAnimator.getAnimatedValue();
            float fOffset = startOffset + deltaOffset * interpolatedTime;
            float velocityInterpolator = (float) Math.sqrt(interpolatedTime) * 3.f;
            if (velocityInterpolator < 1.f)
                fOffset -= velocity / mWindowSize.x * velocityInterpolator;
            else
                fOffset -= velocity / mWindowSize.x * (1.f - 0.5f * (velocityInterpolator - 1.f));
            updateWallpaperOffset(fOffset);
        });
        mWallpaperAnimator = animator;
        animator.start();
    }

    private void sendTouchEvent(int x, int y, int index) {
        android.os.IBinder iBinder = getWindowToken();
        if (iBinder != null) {
            String command = index == 0 ? WallpaperManager.COMMAND_TAP : WallpaperManager.COMMAND_SECONDARY_TAP;
            mWallpaperManager.sendWallpaperCommand(iBinder, command, x, y, 0, null);
        }
    }

    private void sendTouchEvent(View view, MotionEvent event) {
        int pointerCount = event.getPointerCount();
        int[] viewOffset = {0, 0};
        // this will not account for a rotated view
        view.getLocationOnScreen(viewOffset);

        // get index of first finger
        int pointerIndex = event.findPointerIndex(0);
        if (pointerIndex >= 0 && pointerIndex < pointerCount) {
            sendTouchEvent((int) event.getX(pointerIndex) + viewOffset[0], (int) event.getY(pointerIndex) + viewOffset[1], pointerIndex);
        }

        // get index of second finger
        pointerIndex = event.findPointerIndex(1);
        if (pointerIndex >= 0 && pointerIndex < pointerCount) {
            sendTouchEvent((int) event.getX(pointerIndex) + viewOffset[0], (int) event.getY(pointerIndex) + viewOffset[1], pointerIndex);
        }
    }
}
