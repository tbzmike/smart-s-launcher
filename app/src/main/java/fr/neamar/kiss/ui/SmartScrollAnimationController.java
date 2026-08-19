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
