package fr.neamar.kiss.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.preference.PreferenceManager;

/**
 * Launcher-owned animation helpers. These animations run on Smart S views directly instead of
 * depending on OEM/system window animation policies.
 */
public final class SmartAnimationEngine {
    private SmartAnimationEngine() {}

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean("smart-animations-enabled", true);
    }

    public static String getStyle(Context context, String key, String fallback) {
        Object value = prefs(context).getAll().get(key);
        return value instanceof String ? (String) value : fallback;
    }

    public static long duration(Context context) {
        int base = context.getResources().getInteger(android.R.integer.config_shortAnimTime);
        SharedPreferences preferences = prefs(context);
        float speed = readSpeedMultiplier(preferences);
        speed = Math.max(0.05f, Math.min(3f, speed));
        // A lower speed means a longer animation; a higher speed means a shorter animation.
        return Math.max(80L, Math.round(base / speed));
    }

    private static float readSpeedMultiplier(SharedPreferences preferences) {
        Object percentValue = preferences.getAll().get("smart-animation-speed-percent");
        Float percent = parseNumber(percentValue);
        if (percent != null) return percent / 100f;

        Object legacyValue = preferences.getAll().get("smart-animation-speed");
        Float legacy = parseNumber(legacyValue);
        return legacy == null ? 1f : legacy;
    }

    private static Float parseNumber(Object value) {
        if (value instanceof Number) return ((Number) value).floatValue();
        if (value instanceof String) {
            try {
                return Float.parseFloat((String) value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static void animateDialogIn(Dialog dialog) {
        animateDialogIn(dialog, "smart-animation-popup-open", "scale");
    }

    public static void animateNotificationExpand(Dialog dialog) {
        animateDialogIn(dialog, "smart-animation-notification-expand", "spring");
    }

    private static void animateDialogIn(Dialog dialog, String preferenceKey, String fallback) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        animateViewIn(window.getDecorView(), preferenceKey, fallback);
    }

    /** Animate non-Dialog popup content using the same popup preference as dialogs. */
    public static void animatePopupViewIn(View view) {
        animateViewIn(view, "smart-animation-popup-open", "scale");
    }

    /** Animate non-Dialog popup content out, then run the actual dismiss action. */
    public static void animatePopupViewOut(View view, Runnable endAction) {
        if (view == null) {
            if (endAction != null) endAction.run();
            return;
        }
        view.animate().cancel();
        if (!isEnabled(view.getContext())) {
            reset(view);
            if (endAction != null) endAction.run();
            return;
        }
        String style = getStyle(view.getContext(), "smart-animation-popup-close", "shrink");
        if ("none".equals(style)) {
            reset(view);
            if (endAction != null) endAction.run();
            return;
        }

        android.view.ViewPropertyAnimator animator = view.animate()
                .setDuration(Math.max(70L, duration(view.getContext()) * 3 / 4))
                .setInterpolator(new AccelerateDecelerateInterpolator());
        applyExitStyle(view, animator, style);
        animator.setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.animate().setListener(null);
                reset(view);
                if (endAction != null) endAction.run();
            }
        }).start();
    }

    private static void animateViewIn(View view, String preferenceKey, String fallback) {
        if (view == null) return;
        view.animate().cancel();
        reset(view);

        if (!isEnabled(view.getContext())) return;
        String style = getStyle(view.getContext(), preferenceKey, fallback);
        if ("none".equals(style)) return;

        applyEnterStart(view, style);
        view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .rotation(0f)
                .setDuration(duration(view.getContext()))
                .setInterpolator("spring".equals(style)
                        ? new OvershootInterpolator(0.9f)
                        : new DecelerateInterpolator())
                .start();
    }

    private static void applyEnterStart(View view, String style) {
        switch (style) {
            case "fade":
                view.setAlpha(0f);
                break;
            case "slide-up":
                view.setAlpha(0f);
                view.setTranslationY(dp(view, 42));
                break;
            case "slide-down":
                view.setAlpha(0f);
                view.setTranslationY(-dp(view, 42));
                break;
            case "slide-left":
                view.setAlpha(0f);
                view.setTranslationX(dp(view, 48));
                break;
            case "slide-right":
                view.setAlpha(0f);
                view.setTranslationX(-dp(view, 48));
                break;
            case "zoom":
                view.setAlpha(0f);
                view.setScaleX(0.72f);
                view.setScaleY(0.72f);
                break;
            case "spring":
                view.setAlpha(0f);
                view.setScaleX(0.82f);
                view.setScaleY(0.82f);
                view.setTranslationY(dp(view, 18));
                break;
            case "rotate":
                view.setAlpha(0f);
                view.setScaleX(0.9f);
                view.setScaleY(0.9f);
                view.setRotation(5f);
                break;
            case "scale":
            default:
                view.setAlpha(0f);
                view.setScaleX(0.94f);
                view.setScaleY(0.94f);
                break;
        }
    }

    public static void dismissDialog(Dialog dialog) {
        if (dialog == null || !dialog.isShowing()) return;
        Window window = dialog.getWindow();
        if (window == null) {
            dialog.dismiss();
            return;
        }
        View decor = window.getDecorView();
        decor.animate().cancel();

        if (!isEnabled(decor.getContext())) {
            dialog.dismiss();
            return;
        }
        String style = getStyle(decor.getContext(), "smart-animation-popup-close", "shrink");
        if ("none".equals(style)) {
            dialog.dismiss();
            return;
        }

        android.view.ViewPropertyAnimator animator = decor.animate()
                .setDuration(Math.max(70L, duration(decor.getContext()) * 3 / 4))
                .setInterpolator(new AccelerateDecelerateInterpolator());
        applyExitStyle(decor, animator, style);
        animator.setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                decor.animate().setListener(null);
                reset(decor);
                if (dialog.isShowing()) dialog.dismiss();
            }
        }).start();
    }

    private static void applyExitStyle(View view, android.view.ViewPropertyAnimator animator,
                                       String style) {
        switch (style) {
            case "fade":
                animator.alpha(0f);
                break;
            case "slide-down":
                animator.alpha(0f).translationY(dp(view, 42));
                break;
            case "slide-up":
                animator.alpha(0f).translationY(-dp(view, 42));
                break;
            case "slide-left":
                animator.alpha(0f).translationX(-dp(view, 48));
                break;
            case "slide-right":
                animator.alpha(0f).translationX(dp(view, 48));
                break;
            case "zoom":
                animator.alpha(0f).scaleX(1.18f).scaleY(1.18f);
                break;
            case "rotate":
                animator.alpha(0f).scaleX(0.92f).scaleY(0.92f).rotation(-5f);
                break;
            case "shrink":
            default:
                animator.alpha(0f).scaleX(0.93f).scaleY(0.93f);
                break;
        }
    }

    public static void animateWindowSwitch(View outgoing, View incoming) {
        if (incoming == null) return;
        Context context = incoming.getContext();
        if (!isEnabled(context)) return;
        long duration = duration(context);
        String style = getStyle(context, "smart-animation-view-switch", "crossfade");
        if ("none".equals(style)) return;

        if (outgoing != null) {
            outgoing.animate().cancel();
            if ("slide".equals(style)) {
                outgoing.animate().alpha(0f).translationX(-dp(outgoing, 24)).setDuration(duration / 2).start();
            } else if ("depth".equals(style)) {
                outgoing.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(duration / 2).start();
            } else if ("zoom".equals(style)) {
                outgoing.animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setDuration(duration / 2).start();
            } else {
                outgoing.animate().alpha(0f).setDuration(duration / 2).start();
            }
        }

        incoming.animate().cancel();
        reset(incoming);
        incoming.setAlpha(0f);
        if ("slide".equals(style)) incoming.setTranslationX(dp(incoming, 28));
        else if ("depth".equals(style)) {
            incoming.setScaleX(1.04f);
            incoming.setScaleY(1.04f);
        } else if ("zoom".equals(style)) {
            incoming.setScaleX(0.9f);
            incoming.setScaleY(0.9f);
        }
        incoming.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(duration)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    public static void animateListMove(View child, int delta, boolean isNew) {
        if (child == null || !isEnabled(child.getContext())) return;
        long duration = duration(child.getContext());
        child.animate().cancel();
        if (isNew) {
            child.setAlpha(0f);
            child.setScaleY(0.92f);
            child.setTranslationY(Math.min(48f, Math.max(-48f, delta)));
            child.animate()
                    .alpha(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(duration)
                    .setInterpolator(new OvershootInterpolator(0.7f))
                    .start();
        } else if (delta != 0) {
            child.setTranslationY(delta);
            child.animate()
                    .translationY(0f)
                    .setDuration(duration)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    public static void reset(View view) {
        view.setAlpha(1f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setRotation(0f);
        view.setRotationX(0f);
        view.setRotationY(0f);
    }

    private static float dp(View view, int value) {
        return value * view.getResources().getDisplayMetrics().density;
    }
}
