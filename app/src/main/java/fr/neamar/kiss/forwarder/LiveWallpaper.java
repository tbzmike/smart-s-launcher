package fr.neamar.kiss.forwarder;

import android.animation.ValueAnimator;
import android.app.WallpaperManager;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Point;
import android.os.Build;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.StyleableRes;
import androidx.core.content.ContextCompat;

import fr.neamar.kiss.MainActivity;

class LiveWallpaper extends Forwarder implements SharedPreferences.OnSharedPreferenceChangeListener {

    private final boolean wallpaperIsVisible;

    private WallpaperManager mWallpaperManager;
    protected Point mWindowSize;
    private android.os.IBinder mWindowToken;
    private View mContentView;
    private float mLastTouchPos;
    private float mWallpaperOffset;
    private LiveWallpaper.Anim mAnimation;
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
        mWallpaperOffset = 0.5f;
        mAnimation = new Anim();
        mVelocityTracker = null;
        mWindowSize = new Point(1, 1);

        // Smart Blur used to exist only as preferences. Keep the renderer here, beside
        // the wallpaper lifecycle, so list/tile redraws never recompute the blur.
        prefs.registerOnSharedPreferenceChangeListener(this);
        applySmartBlur();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null || key.startsWith("smart-focus-") || "smart-history-background-blur".equals(key)
                || "smart-blur-performance".equals(key)) {
            applySmartBlur();
        }
    }

    private void applySmartBlur() {
        if (!wallpaperIsVisible) {
            return;
        }

        WindowManager.LayoutParams attributes = mainActivity.getWindow().getAttributes();
        // smart-focus-blur-enabled is the master Smart Blur switch. History background blur is
        // a subordinate behaviour and must never keep FLAG_BLUR_BEHIND alive after the master
        // switch is turned off.
        boolean enabled = prefs.getBoolean("smart-focus-blur-enabled", false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && enabled) {
            int radius = resolveBlurRadius();
            attributes.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            attributes.setBlurBehindRadius(radius);
        } else {
            attributes.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                attributes.setBlurBehindRadius(0);
            }
        }
        mainActivity.getWindow().setAttributes(attributes);
        // Force a fresh window composition immediately. Some Android 12+ compositors retain the
        // previous blur frame until the decor is invalidated even after the radius reaches zero.
        View decor = mainActivity.getWindow().getDecorView();
        decor.invalidate();
        decor.requestLayout();
    }

    private int resolveBlurRadius() {
        String strength = prefs.getString("smart-focus-strength", "balanced");
        String radius = prefs.getString("smart-focus-radius", "medium");
        String performance = prefs.getString("smart-blur-performance", "balanced");

        float strengthFactor;
        switch (strength) {
            case "light": strengthFactor = 0.65f; break;
            case "strong": strengthFactor = 1.35f; break;
            default: strengthFactor = 1.0f; break;
        }

        int baseRadius;
        switch (radius) {
            case "small": baseRadius = 24; break;
            case "large": baseRadius = 72; break;
            default: baseRadius = 48; break;
        }

        float performanceFactor;
        switch (performance) {
            case "battery": performanceFactor = 0.65f; break;
            case "quality": performanceFactor = 1.25f; break;
            default: performanceFactor = 1.0f; break;
        }
        return Math.max(1, Math.min(120, Math.round(baseRadius * strengthFactor * performanceFactor)));
    }

    boolean onTouch(View view, MotionEvent event) {
        if (!wallpaperIsVisible) {
            return false;
        }

        int actionMasked = event.getActionMasked();
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
                if (isPreferenceWPDragAnimate()) {
                    mAnimation.cancel();
                    mVelocityTracker = VelocityTracker.obtain();
                    mVelocityTracker.addMovement(event);
                    mLastTouchPos = event.getRawX();
                    mainActivity.getWindowManager().getDefaultDisplay().getSize(mWindowSize);
                }
                if (isPreferenceLWPTouch()) sendTouchEvent(view, event);
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
                if (isPreferenceLWPDrag()) sendTouchEvent(view, event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mVelocityTracker != null) {
                    mVelocityTracker.addMovement(event);
                    mVelocityTracker.computeCurrentVelocity(1000 / 30);
                    if (mAnimation.init(isPreferenceWPStickToSides(), isPreferenceWPReturnCenter(), mVelocityTracker.getXVelocity(), mWallpaperOffset))
                        mAnimation.start();
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
        }
        return false;
    }

    private boolean isPreferenceLWPTouch() { return prefs.getBoolean("lwp-touch", true); }
    private boolean isPreferenceLWPDrag() { return prefs.getBoolean("lwp-drag", false); }
    private boolean isPreferenceWPDragAnimate() { return prefs.getBoolean("wp-drag-animate", false); }
    private boolean isPreferenceWPReturnCenter() { return prefs.getBoolean("wp-animate-center", true); }
    private boolean isPreferenceWPStickToSides() { return prefs.getBoolean("wp-animate-sides", false); }

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
        view.getLocationOnScreen(viewOffset);
        int pointerIndex = event.findPointerIndex(0);
        if (pointerIndex >= 0 && pointerIndex < pointerCount) {
            sendTouchEvent((int) event.getX(pointerIndex) + viewOffset[0], (int) event.getY(pointerIndex) + viewOffset[1], pointerIndex);
        }
        pointerIndex = event.findPointerIndex(1);
        if (pointerIndex >= 0 && pointerIndex < pointerCount) {
            sendTouchEvent((int) event.getX(pointerIndex) + viewOffset[0], (int) event.getY(pointerIndex) + viewOffset[1], pointerIndex);
        }
    }

    private class Anim {
        float mStartOffset = 0.5f;
        float mDeltaOffset = 0;
        float mVelocity = 0;
        private ValueAnimator animator;

        boolean init(boolean stickToSides, boolean stickToCenter, float velocity, float wallpaperOffset) {
            mVelocity = velocity;
            mStartOffset = wallpaperOffset;
            float expectedPos = -Math.min(Math.max(mVelocity / LiveWallpaper.this.mWindowSize.x, -.5f), .5f) + mStartOffset;
            float leftStickPercent = -1.f;
            float rightStickPercent = 2.f;
            if (stickToSides && stickToCenter) {
                leftStickPercent = .2f;
                rightStickPercent = .8f;
            } else if (stickToSides) {
                leftStickPercent = .5f;
                rightStickPercent = .5f;
            }
            if (expectedPos <= leftStickPercent) mDeltaOffset = 0.f - mStartOffset;
            else if (expectedPos >= rightStickPercent) mDeltaOffset = 1.f - mStartOffset;
            else if (stickToCenter) mDeltaOffset = .5f - mStartOffset;
            else return false;
            return true;
        }

        void start() {
            cancel();
            animator = ValueAnimator.ofFloat(0.f, 1.f);
            animator.setDuration(1000);
            animator.addUpdateListener(valueAnimator -> applyTransformation((float) valueAnimator.getAnimatedValue()));
            animator.start();
        }

        void cancel() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        private void applyTransformation(float interpolatedTime) {
            float fOffset = mStartOffset + mDeltaOffset * interpolatedTime;
            float velocityInterpolator = (float) Math.sqrt(interpolatedTime) * 3.f;
            if (velocityInterpolator < 1.f) fOffset -= mVelocity / LiveWallpaper.this.mWindowSize.x * velocityInterpolator;
            else fOffset -= mVelocity / LiveWallpaper.this.mWindowSize.x * (1.f - 0.5f * (velocityInterpolator - 1.f));
            LiveWallpaper.this.updateWallpaperOffset(fOffset);
        }
    }
}