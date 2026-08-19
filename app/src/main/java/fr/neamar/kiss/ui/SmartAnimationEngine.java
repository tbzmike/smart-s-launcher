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
        return prefs(context).getString(key, fallback);
    }

    public static long duration(Context context) {
        int base = context.getResources().getInteger(android.R.integer.config_shortAnimTime);
        SharedPreferences preferences = prefs(context);
        float speed = 1f;

        if (preferences.contains("smart-animation-speed-percent")) {
            try {
                int percent = preferences.getInt("smart-animation-speed-percent", 100);
                speed = percent / 100f;
            } catch (ClassCastException ignored) {
                speed = 1f;
            }
        } else {
            try {
                speed = Float.parseFloat(preferences.getString("smart-animation-speed", "1.0"));
            } catch (ClassCastException | NumberFormatException ignored) {
                speed = 1f;
            }
        }

        speed = Math.max(0.05f, Math.min(3f, speed));
        // A lower speed means a longer animation; a higher speed means a shorter animation.
        return Math.max(80L, Math.round(base / speed));
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
        View decor = window.getDecorView();
        decor.animate().cancel();
        reset(decor);

        if (!isEnabled(decor.getContext())) return;
        String style = getStyle(decor.getContext(), preferenceKey, fallback);
        if ("none".equals(style)) return;

        switch (style) {
            case "fade":
                decor.setAlpha(0f);
                break;
            case "slide-up":
                decor.setAlpha(0f);
                decor.setTranslationY(dp(decor, 42));
                break;
            case "slide-down":
                decor.setAlpha(0f);
                decor.setTranslationY(-dp(decor, 42));
                break;
            case "slide-left":
                decor.setAlpha(0f);
                decor.setTranslationX(dp(decor, 48));
                break;
            case "slide-right":
                decor.setAlpha(0f);
                decor.setTranslationX(-dp(decor, 48));
                break;
            case "zoom":
                decor.setAlpha(0f);
                decor.setScaleX(0.72f);
                decor.setScaleY(0.72f);
                break;
            case "spring":
                decor.setAlpha(0f);
                decor.setScaleX(0.82f);
                decor.setScaleY(0.82f);
                decor.setTranslationY(dp(decor, 18));
                break;
            case "rotate":
                decor.setAlpha(0f);
                decor.setScaleX(0.9f);
                decor.setScaleY(0.9f);
                decor.setRotation(5f);
                break;
            case "scale":
            default:
                decor.setAlpha(0f);
                decor.setScaleX(0.94f);
                decor.setScaleY(0.94f);
                break;
        }

        decor.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .rotation(0f)
                .setDuration(duration(decor.getContext()))
                .setInterpolator("spring".equals(style)
                        ? new OvershootInterpolator(0.9f)
                        : new DecelerateInterpolator())
                .start();
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
        switch (style) {
            case "fade":
                animator.alpha(0f);
                break;
            case "slide-down":
                animator.alpha(0f).translationY(dp(decor, 42));
                break;
            case "slide-up":
                animator.alpha(0f).translationY(-dp(decor, 42));
                break;
            case "slide-left":
                animator.alpha(0f).translationX(-dp(decor, 48));
                break;
            case "slide-right":
                animator.alpha(0f).translationX(dp(decor, 48));
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
        animator.setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                decor.animate().setListener(null);
                reset(decor);
                if (dialog.isShowing()) dialog.dismiss();
            }
        }).start();
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
