package fr.neamar.kiss.forwarder;

import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.preference.PreferenceManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.utils.Log;

/**
 * Cross-layer wiring guard for the three Smart-U updates.
 * Keeps motion profiles authoritative, refreshes live Smart Center data while visible,
 * and forces a renderer layout after style/profile changes so transforms never accumulate.
 */
final class SmartUIntegrationAuditForwarder {
    private static final String TAG = SmartUIntegrationAuditForwarder.class.getSimpleName();
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String PREF_PROFILE = "smart-u-motion-profile";
    private static final String PREF_STYLE = "smart-u-visual-style";
    private static final String SQUARE_U = "square_u";
    private static final long LIVE_REFRESH_MS = 30_000L;

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final SmartUIntelligenceForwarder intelligenceForwarder;
    private final android.content.SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int minFlingVelocity;
    private final int maxFlingVelocity;

    private ViewGroup squareTrack;
    private VelocityTracker velocityTracker;
    private float downX;
    private float downY;
    private boolean resumed;

    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (sharedPreferences, key) -> {
                if ((PREF_PROFILE.equals(key) || PREF_STYLE.equals(key)) && isUStyle()) {
                    resolveTrack();
                    if (squareTrack != null) {
                        // Re-run the renderer's layout transform before polish is applied again.
                        squareTrack.requestLayout();
                        squareTrack.invalidate();
                    }
                }
            };

    private final Runnable liveRefresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (isUStyle()) {
                invokeIntelligenceRefresh();
                handler.postDelayed(this, LIVE_REFRESH_MS);
            }
        }
    };

    SmartUIntegrationAuditForwarder(MainActivity activity,
                                    HistoryDisplayForwarder historyDisplayForwarder,
                                    SmartUIntelligenceForwarder intelligenceForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
        this.intelligenceForwarder = intelligenceForwarder;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        ViewConfiguration configuration = ViewConfiguration.get(activity);
        minFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        maxFlingVelocity = configuration.getScaledMaximumFlingVelocity();
    }

    void onCreate() {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
        resolveTrack();
        installProfileAwareTouch();
    }

    void onResume() {
        resumed = true;
        resolveTrack();
        installProfileAwareTouch();
        handler.removeCallbacks(liveRefresh);
        handler.post(liveRefresh);
    }

    void onPause() {
        resumed = false;
        handler.removeCallbacks(liveRefresh);
        recycleVelocityTracker();
    }

    void onDataSetChanged() {
        resolveTrack();
        if (isUStyle()) installProfileAwareTouch();
        else if (squareTrack != null) squareTrack.setOnTouchListener(null);
    }

    void onConfigurationChanged() {
        resolveTrack();
        if (isUStyle()) {
            installProfileAwareTouch();
            invokeIntelligenceRefresh();
        }
    }

    void onDestroy() {
        resumed = false;
        handler.removeCallbacksAndMessages(null);
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        recycleVelocityTracker();
        if (squareTrack != null) squareTrack.setOnTouchListener(null);
        squareTrack = null;
    }

    private boolean isUStyle() {
        return SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"));
    }

    private void resolveTrack() {
        try {
            Field field = HistoryDisplayForwarder.class.getDeclaredField("squareTrack");
            field.setAccessible(true);
            Object value = field.get(historyDisplayForwarder);
            squareTrack = value instanceof ViewGroup ? (ViewGroup) value : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Unable to resolve Square-U track", e);
            squareTrack = null;
        }
    }

    private void installProfileAwareTouch() {
        if (squareTrack == null || !isUStyle()) return;
        squareTrack.setOnTouchListener(this::observeGesture);
    }

    private boolean observeGesture(View view, MotionEvent event) {
        if (!isUStyle()) return false;
        trackVelocity(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                float velocityX = computeVelocityX();
                recycleVelocityTracker();
                String profile = prefs.getString(PREF_PROFILE, "smooth");
                if (!"off".equals(profile)
                        && Math.abs(dx) > Math.abs(dy) * 1.15f
                        && Math.abs(velocityX) >= minFlingVelocity) {
                    view.post(() -> applyProjectedSettle(velocityX, profile));
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                recycleVelocityTracker();
                break;
            default:
                break;
        }
        // Observe only; SquareTrackLayout still receives and owns the gesture.
        return false;
    }

    private void trackVelocity(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN || velocityTracker == null) {
            recycleVelocityTracker();
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);
    }

    private float computeVelocityX() {
        if (velocityTracker == null) return 0f;
        velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
        return velocityTracker.getXVelocity();
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void applyProjectedSettle(float velocityX, String profile) {
        if (!isUStyle() || squareTrack == null || squareTrack.getChildCount() < 2) return;
        try {
            Field offsetField = squareTrack.getClass().getDeclaredField("rotationOffset");
            Field animatorField = squareTrack.getClass().getDeclaredField("settleAnimator");
            offsetField.setAccessible(true);
            animatorField.setAccessible(true);

            float current = offsetField.getFloat(squareTrack);
            Object running = animatorField.get(squareTrack);
            if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();

            float normalized = Math.max(-1f, Math.min(1f, velocityX / Math.max(1f, maxFlingVelocity)));
            float reach = "efficient".equals(profile) ? 2.5f : ("cinematic".equals(profile) ? 4.5f : 4.0f);
            float projectedSlots = normalized * reach;
            if (Math.abs(projectedSlots) < 1f) projectedSlots = Math.signum(velocityX);
            float target = Math.round(current + projectedSlots);
            if (Math.abs(target - current) < 0.5f) target = Math.round(current + Math.signum(velocityX));

            float slots = Math.abs(target - current);
            long duration;
            if ("efficient".equals(profile)) duration = Math.max(105L, Math.min(240L, 105L + Math.round(slots * 34L)));
            else if ("cinematic".equals(profile)) duration = Math.max(210L, Math.min(520L, 220L + Math.round(slots * 72L)));
            else duration = Math.max(150L, Math.min(420L, 170L + Math.round(slots * 58L)));

            ValueAnimator animator = ValueAnimator.ofFloat(current, target);
            animator.setDuration(duration);
            animator.setInterpolator(new DecelerateInterpolator("cinematic".equals(profile) ? 1.35f : 1.65f));
            animator.addUpdateListener(animation -> {
                try {
                    offsetField.setFloat(squareTrack, (float) animation.getAnimatedValue());
                    squareTrack.requestLayout();
                } catch (IllegalAccessException e) {
                    Log.e(TAG, "Unable to animate profile-aware U settle", e);
                }
            });
            animatorField.set(squareTrack, animator);
            animator.start();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Unable to apply profile-aware U settle", e);
        }
    }

    private void invokeIntelligenceRefresh() {
        try {
            Method method = SmartUIntelligenceForwarder.class.getDeclaredMethod("refreshNow");
            method.setAccessible(true);
            method.invoke(intelligenceForwarder);
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "Unable to refresh Smart-U live intelligence", e);
        }
    }
}
