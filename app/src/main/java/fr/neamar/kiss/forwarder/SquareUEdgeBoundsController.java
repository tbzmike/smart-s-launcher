package fr.neamar.kiss.forwarder;

import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.preference.PreferenceManager;

import java.lang.reflect.Field;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.utils.Log;

/**
 * Final edge-mapping pass for Square-U.
 *
 * SquareUStabilityController owns the editable footprint and all resize gestures. This class only
 * maps the already-rendered cards to those bounds using each card's real scale/rotation, so a bound
 * at 0/1 truly reaches the physical left/right edge instead of being pulled inward by a generic
 * half-card safety inset.
 */
final class SquareUEdgeBoundsController {
    private static final String TAG = SquareUEdgeBoundsController.class.getSimpleName();
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";
    private static final String PREF_LEFT = "smart-u-bound-left";
    private static final String PREF_RIGHT = "smart-u-bound-right";
    private static final String PREF_TOP = "smart-u-bound-top";
    private static final String PREF_BOTTOM = "smart-u-bound-bottom";
    private static final float BOTTOM_BAND = 2.55f;
    private static final float EDGE_SNAP = 0.04f;

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final SharedPreferences prefs;

    private ViewGroup squareTrack;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;

    SquareUEdgeBoundsController(MainActivity activity,
                                HistoryDisplayForwarder historyDisplayForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    void onCreate() {
        resolveTrack();
        attachObserver();
        refreshSoon();
    }

    void onResume() {
        resolveTrack();
        attachObserver();
        refreshSoon();
    }

    void onDataSetChanged() {
        resolveTrack();
        attachObserver();
        refreshSoon();
    }

    void onConfigurationChanged() {
        resolveTrack();
        refreshSoon();
    }

    void onPause() {
        // No animation or polling is owned here.
    }

    void onDestroy() {
        detachObserver();
        squareTrack = null;
    }

    private boolean isUStyle() {
        return SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"));
    }

    private void resolveTrack() {
        ViewGroup next = readField("squareTrack", ViewGroup.class);
        if (next != squareTrack) {
            detachObserver();
            squareTrack = next;
        }
    }

    private <T> T readField(String name, Class<T> type) {
        try {
            Field field = HistoryDisplayForwarder.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(historyDisplayForwarder);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Unable to resolve Square-U field: " + name, e);
            return null;
        }
    }

    private void attachObserver() {
        if (squareTrack == null || layoutListener != null) return;
        layoutListener = this::applyEdgeMapping;
        squareTrack.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    private void detachObserver() {
        if (squareTrack != null && layoutListener != null) {
            ViewTreeObserver observer = squareTrack.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
        }
        layoutListener = null;
    }

    private void refreshSoon() {
        if (squareTrack != null) squareTrack.post(this::applyEdgeMapping);
    }

    private void applyEdgeMapping() {
        if (!isUStyle() || squareTrack == null || squareTrack.getChildCount() == 0) return;
        int width = squareTrack.getWidth();
        int height = squareTrack.getHeight();
        if (width <= 0 || height <= 0) return;

        float leftBound = snapLeft(prefs.getFloat(PREF_LEFT, 0f));
        float rightBound = snapRight(prefs.getFloat(PREF_RIGHT, 1f));
        float topBound = clamp(prefs.getFloat(PREF_TOP, 0.19f), 0f, 1f);
        float bottomBound = clamp(prefs.getFloat(PREF_BOTTOM, 0.90f), 0f, 1f);

        float outerLeft = width * leftBound;
        float outerRight = width * rightBound;
        float outerTop = height * topBound;
        float outerBottom = height * bottomBound;
        float rotationOffset = readRotationOffset();
        int count = squareTrack.getChildCount();

        for (int i = 0; i < count; i++) {
            View card = squareTrack.getChildAt(i);
            if (card.getVisibility() != View.VISIBLE || card.getWidth() <= 0 || card.getHeight() <= 0) {
                continue;
            }

            int recencyIndex = (count - 1) - i;
            float relative = cyclicRelative(recencyIndex + rotationOffset, count);
            float absolute = Math.abs(relative);

            float scaleX = Math.abs(card.getScaleX());
            float scaleY = Math.abs(card.getScaleY());
            float cosY = (float) Math.abs(Math.cos(Math.toRadians(card.getRotationY())));
            // Do not let 3D foreshortening make the edge target vanish completely.
            float projectedHalfW = card.getWidth() * scaleX * Math.max(0.72f, cosY) * 0.5f;
            float projectedHalfH = card.getHeight() * scaleY * 0.5f;

            float targetCenterX;
            float targetCenterY = card.getY() + card.getHeight() * 0.5f;

            if (absolute <= BOTTOM_BAND) {
                float normalized = relative / BOTTOM_BAND;
                float leftCenter = outerLeft + projectedHalfW;
                float rightCenter = outerRight - projectedHalfW;
                targetCenterX = leftCenter + ((normalized + 1f) * 0.5f) * (rightCenter - leftCenter);
                // Keep the front/bottom of the U anchored to the editable bottom edge.
                targetCenterY = Math.min(outerBottom - projectedHalfH,
                        targetCenterY + Math.max(0f, outerBottom - projectedHalfH - targetCenterY));
            } else if (relative < 0f) {
                targetCenterX = outerLeft + projectedHalfW;
                targetCenterY = clamp(targetCenterY,
                        outerTop + projectedHalfH, outerBottom - projectedHalfH);
            } else {
                targetCenterX = outerRight - projectedHalfW;
                targetCenterY = clamp(targetCenterY,
                        outerTop + projectedHalfH, outerBottom - projectedHalfH);
            }

            float currentCenterX = card.getLeft() + card.getWidth() * 0.5f + card.getTranslationX();
            float currentCenterY = card.getTop() + card.getHeight() * 0.5f + card.getTranslationY();
            card.setTranslationX(card.getTranslationX() + (targetCenterX - currentCenterX));
            card.setTranslationY(card.getTranslationY() + (targetCenterY - currentCenterY));
        }
    }

    private float readRotationOffset() {
        if (squareTrack == null) return 0f;
        try {
            Field field = squareTrack.getClass().getDeclaredField("rotationOffset");
            field.setAccessible(true);
            return field.getFloat(squareTrack);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return 0f;
        }
    }

    private float snapLeft(float value) {
        value = clamp(value, 0f, 1f);
        return value <= EDGE_SNAP ? 0f : value;
    }

    private float snapRight(float value) {
        value = clamp(value, 0f, 1f);
        return value >= 1f - EDGE_SNAP ? 1f : value;
    }

    private float cyclicRelative(float value, int count) {
        if (count <= 1) return 0f;
        float half = count / 2f;
        while (value > half) value -= count;
        while (value < -half) value += count;
        return value;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
