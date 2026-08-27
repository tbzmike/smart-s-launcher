package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ListView;

import androidx.preference.PreferenceManager;

/** Applies lightweight per-row transforms during normal launcher scrolling. */
final class SmartScrollAnimationController {
    private final ListView listView;
    private final SharedPreferences prefs;

    SmartScrollAnimationController(ListView listView) {
        this.listView = listView;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(listView.getContext());
    }

    void apply() {
        if (!prefs.getBoolean("smart-animations-enabled", true)) {
            resetChildren();
            return;
        }
        String style = prefs.getString("smart-animation-scroll", "classic");
        if (style == null || "none".equals(style) || "classic".equals(style)) {
            resetChildren();
            return;
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
            SmartAnimationEngine.reset(child);

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
                    SmartAnimationEngine.reset(child);
                    break;
            }
        }
    }

    void resetChildren() {
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child != null) SmartAnimationEngine.reset(child);
        }
    }

    private float dp(int value) {
        Context context = listView.getContext();
        return value * context.getResources().getDisplayMetrics().density;
    }
}
