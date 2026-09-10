package fr.neamar.kiss.ui;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.ListView;

import androidx.preference.PreferenceManager;

/** Applies lightweight per-row transforms during normal launcher scrolling. */
final class SmartScrollAnimationController {
    private final ListView listView;
    private final SharedPreferences prefs;
    private final float density;
    private boolean frameScheduled;
    private boolean transformsActive;
    private String activeStyle;

    SmartScrollAnimationController(ListView listView) {
        this.listView = listView;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(listView.getContext());
        this.density = listView.getResources().getDisplayMetrics().density;
    }

    void requestApply() {
        boolean enabled = prefs.getBoolean("smart-animations-enabled", true);
        String style = prefs.getString("smart-animation-scroll", "classic");
        boolean needsTransforms = enabled && style != null
                && !"none".equals(style) && !"classic".equals(style);

        // Classic/none scrolling has no continuous transform. Do not enqueue a frame callback on
        // every scroll event merely to rewrite identity properties that are already at identity.
        if (!needsTransforms && !transformsActive) return;
        if (frameScheduled) return;

        frameScheduled = true;
        listView.postOnAnimation(() -> {
            frameScheduled = false;
            apply();
        });
    }

    private void apply() {
        if (!prefs.getBoolean("smart-animations-enabled", true)) {
            clearTransformsIfNeeded();
            return;
        }

        String style = prefs.getString("smart-animation-scroll", "classic");
        if (style == null || "none".equals(style) || "classic".equals(style)) {
            clearTransformsIfNeeded();
            return;
        }

        // A style change can leave properties behind that the new style does not own. Reset once
        // at the transition rather than resetting every property on every child every frame.
        if (!transformsActive || !style.equals(activeStyle)) {
            resetChildren();
            transformsActive = true;
            activeStyle = style;
        }

        float center = listView.getHeight() / 2f;
        if (center <= 0f) return;
        int count = listView.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = listView.getChildAt(i);
            if (child == null) continue;
            float childCenter = (child.getTop() + child.getBottom()) / 2f;
            float distance = Math.max(-1f, Math.min(1f, (childCenter - center) / center));
            float abs = Math.abs(distance);

            switch (style) {
                case "focus":
                    child.setAlpha(0.62f + (1f - abs) * 0.38f);
                    child.setScaleX(0.94f + (1f - abs) * 0.06f);
                    child.setScaleY(0.94f + (1f - abs) * 0.06f);
                    break;
                case "depth":
                    child.setScaleX(1f - abs * 0.08f);
                    child.setScaleY(1f - abs * 0.08f);
                    child.setAlpha(1f - abs * 0.24f);
                    break;
                case "wave":
                    child.setTranslationX((float) Math.sin(distance * Math.PI) * dp(18));
                    child.setScaleX(1f - abs * 0.03f);
                    child.setScaleY(1f - abs * 0.03f);
                    break;
                case "slide":
                    child.setTranslationX(distance * dp(24));
                    child.setAlpha(1f - abs * 0.18f);
                    break;
                case "stack":
                    child.setTranslationY(-distance * dp(10));
                    child.setScaleX(1f - abs * 0.06f);
                    child.setScaleY(1f - abs * 0.06f);
                    break;
                case "zoom":
                    child.setScaleX(0.9f + (1f - abs) * 0.1f);
                    child.setScaleY(0.9f + (1f - abs) * 0.1f);
                    break;
                case "tilt":
                    child.setRotationX(distance * -7f);
                    child.setScaleX(1f - abs * 0.035f);
                    child.setScaleY(1f - abs * 0.035f);
                    break;
                case "cascade":
                    child.setTranslationX(abs * dp(26));
                    child.setAlpha(1f - abs * 0.22f);
                    break;
                case "flip":
                    child.setRotationY(distance * -34f);
                    child.setScaleX(1f - abs * 0.08f);
                    child.setAlpha(1f - abs * 0.18f);
                    break;
                case "whirl":
                    child.setRotation(distance * 14f);
                    child.setScaleX(1f - abs * 0.10f);
                    child.setScaleY(1f - abs * 0.10f);
                    child.setTranslationX((float) Math.sin(distance * Math.PI) * dp(20));
                    break;
                case "orbit":
                    child.setTranslationX(distance * dp(34));
                    child.setTranslationY(abs * dp(8));
                    child.setRotationY(distance * -26f);
                    child.setScaleX(1f - abs * 0.08f);
                    child.setScaleY(1f - abs * 0.05f);
                    break;
                case "elastic":
                    child.setScaleX(1f - abs * 0.18f);
                    child.setScaleY(1f + abs * 0.08f);
                    child.setTranslationY(distance * dp(12));
                    break;
                case "bounce":
                    child.setTranslationY((float) Math.sin(abs * Math.PI) * dp(16));
                    child.setScaleX(1f - abs * 0.06f);
                    child.setScaleY(1f - abs * 0.10f);
                    break;
                case "helix":
                    child.setTranslationX((float) Math.sin(distance * Math.PI) * dp(42));
                    child.setRotationY(distance * -42f);
                    child.setRotation(distance * 6f);
                    child.setScaleX(1f - abs * 0.12f);
                    child.setScaleY(1f - abs * 0.08f);
                    child.setAlpha(1f - abs * 0.16f);
                    break;
                case "fan":
                    child.setPivotX(distance < 0f ? 0f : child.getWidth());
                    child.setPivotY(child.getHeight());
                    child.setRotation(distance * 12f);
                    child.setTranslationX(distance * dp(18));
                    child.setScaleX(1f - abs * 0.07f);
                    child.setScaleY(1f - abs * 0.07f);
                    child.setAlpha(1f - abs * 0.14f);
                    break;
                default:
                    clearTransformsIfNeeded();
                    return;
            }
        }
    }

    private void clearTransformsIfNeeded() {
        if (!transformsActive) return;
        resetChildren();
        transformsActive = false;
        activeStyle = null;
    }

    void resetChildren() {
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child != null) SmartAnimationEngine.reset(child);
        }
    }

    private float dp(int value) {
        return value * density;
    }
}
