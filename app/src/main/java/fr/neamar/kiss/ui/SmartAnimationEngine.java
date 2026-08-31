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

    private static volatile SharedPreferences cachedPreferences;

    private static SharedPreferences prefs(Context context) {
        SharedPreferences local = cachedPreferences;
        if (local == null) {
            synchronized (SmartAnimationEngine.class) {
                local = cachedPreferences;
                if (local == null) {
                    local = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
                    cachedPreferences = local;
                }
            }
        }
        return local;
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean("smart-animations-enabled", true);
    }

    public static String getStyle(Context context, String key, String fallback) {
        Object value = readPreferenceValue(prefs(context), key);
        return value instanceof String ? (String) value : fallback;
    }

    public static long duration(Context context) {
        int base = context.getResources().getInteger(android.R.integer.config_shortAnimTime);
        SharedPreferences preferences = prefs(context);
        float speed = readSpeedMultiplier(preferences);
        speed = Math.max(0.05f, Math.min(3f, speed));
        return Math.max(80L, Math.round(base / speed));
    }

    private static float readSpeedMultiplier(SharedPreferences preferences) {
        Object percentValue = readPreferenceValue(preferences, "smart-animation-speed-percent");
        Float percent = parseNumber(percentValue);
        if (percent != null) return percent / 100f;

        Object legacyValue = readPreferenceValue(preferences, "smart-animation-speed");
        Float legacy = parseNumber(legacyValue);
        return legacy == null ? 1f : legacy;
    }

    private static Object readPreferenceValue(SharedPreferences preferences, String key) {
        if (!preferences.contains(key)) return null;
        try { return preferences.getString(key, null); } catch (ClassCastException ignored) { }
        try { return preferences.getInt(key, 0); } catch (ClassCastException ignored) { }
        try { return preferences.getFloat(key, 0f); } catch (ClassCastException ignored) { }
        try { return preferences.getLong(key, 0L); } catch (ClassCastException ignored) { }
        return null;
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

    public static void animatePopupViewIn(View view) {
        animateViewIn(view, "smart-animation-popup-open", "scale");
    }

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
                .rotationX(0f)
                .rotationY(0f)
                .setDuration(duration(view.getContext()))
                .setInterpolator(("spring".equals(style) || "elastic".equals(style)
                        || "bounce".equals(style))
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
            case "flip":
                view.setAlpha(0f);
                view.setScaleX(0.88f);
                view.setRotationY(26f);
                break;
            case "whirl":
                view.setAlpha(0f);
                view.setScaleX(0.78f);
                view.setScaleY(0.78f);
                view.setRotation(-16f);
                break;
            case "orbit":
                view.setAlpha(0f);
                view.setScaleX(0.84f);
                view.setScaleY(0.84f);
                view.setTranslationX(dp(view, 38));
                view.setRotationY(-20f);
                break;
            case "elastic":
                view.setAlpha(0f);
                view.setScaleX(0.62f);
                view.setScaleY(1.16f);
                view.setTranslationY(dp(view, 24));
                break;
            case "bounce":
                view.setAlpha(0f);
                view.setScaleX(1.12f);
                view.setScaleY(0.72f);
                view.setTranslationY(dp(view, 36));
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
            case "flip":
                animator.alpha(0f).scaleX(0.88f).rotationY(-26f);
                break;
            case "whirl":
                animator.alpha(0f).scaleX(0.72f).scaleY(0.72f).rotation(18f);
                break;
            case "orbit":
                animator.alpha(0f).translationX(dp(view, 38)).rotationY(20f).scaleX(0.86f);
                break;
            case "elastic":
                animator.alpha(0f).scaleX(1.18f).scaleY(0.62f);
                break;
            case "bounce":
                animator.alpha(0f).translationY(dp(view, 30)).scaleX(0.86f).scaleY(1.12f);
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
            } else if ("flip".equals(style)) {
                outgoing.animate().alpha(0f).rotationY(-22f).scaleX(0.92f).setDuration(duration / 2).start();
            } else if ("whirl".equals(style)) {
                outgoing.animate().alpha(0f).rotation(-12f).scaleX(0.88f).scaleY(0.88f).setDuration(duration / 2).start();
            } else if ("orbit".equals(style)) {
                outgoing.animate().alpha(0f).translationX(-dp(outgoing, 32)).rotationY(18f)
                        .scaleX(0.92f).setDuration(duration / 2).start();
            } else if ("spring".equals(style)) {
                outgoing.animate().alpha(0f).scaleX(0.86f).scaleY(1.08f).setDuration(duration / 2).start();
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
        } else if ("flip".equals(style)) {
            incoming.setRotationY(24f);
            incoming.setScaleX(0.9f);
        } else if ("whirl".equals(style)) {
            incoming.setRotation(14f);
            incoming.setScaleX(0.82f);
            incoming.setScaleY(0.82f);
        } else if ("orbit".equals(style)) {
            incoming.setTranslationX(dp(incoming, 36));
            incoming.setRotationY(-20f);
            incoming.setScaleX(0.9f);
        } else if ("spring".equals(style)) {
            incoming.setScaleX(0.76f);
            incoming.setScaleY(1.08f);
        }
        incoming.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                .rotation(0f).rotationY(0f).setDuration(duration)
                .setInterpolator("spring".equals(style)
                        ? new OvershootInterpolator(0.75f)
                        : new DecelerateInterpolator()).start();
    }

    public static void animateListMove(View child, int delta, boolean isNew) {
        if (child == null || !isEnabled(child.getContext())) return;
        Context context = child.getContext();
        long duration = duration(context);
        child.animate().cancel();

        if (isNew) {
            reset(child);
            String style = getStyle(context, "smart-animation-view-switch", "crossfade");
            if ("none".equals(style)) return;

            child.setAlpha(0f);
            switch (style) {
                case "slide":
                    child.setTranslationX(dp(child, 40));
                    child.setTranslationY(Math.min(72f, Math.max(-72f, delta * 1.5f)));
                    break;
                case "depth":
                    child.setScaleX(1.08f);
                    child.setScaleY(0.88f);
                    child.setTranslationY(Math.min(56f, Math.max(-56f, delta)));
                    break;
                case "zoom":
                    child.setScaleX(0.78f);
                    child.setScaleY(0.78f);
                    child.setTranslationY(Math.min(44f, Math.max(-44f, delta)));
                    break;
                case "flip":
                    child.setScaleX(0.9f);
                    child.setRotationY(delta >= 0 ? 20f : -20f);
                    break;
                case "whirl":
                    child.setScaleX(0.84f);
                    child.setScaleY(0.84f);
                    child.setRotation(delta >= 0 ? 9f : -9f);
                    break;
                case "orbit":
                    child.setTranslationX(dp(child, delta >= 0 ? 38 : -38));
                    child.setRotationY(delta >= 0 ? -20f : 20f);
                    break;
                case "spring":
                    child.setScaleX(0.74f);
                    child.setScaleY(1.1f);
                    break;
                case "crossfade":
                default:
                    child.setScaleX(0.95f);
                    child.setScaleY(0.95f);
                    child.setTranslationY(Math.min(64f, Math.max(-64f, delta * 1.35f)));
                    break;
            }

            child.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .rotation(0f)
                    .rotationY(0f)
                    .setDuration(duration)
                    .setInterpolator(("depth".equals(style) || "spring".equals(style))
                            ? new OvershootInterpolator(0.65f)
                            : new DecelerateInterpolator())
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

    /** Entrance animation for tile/card lists, controlled by the global scroll-animation setting. */
    public static void animateTileListItem(View child, int index) {
        if (child == null) return;
        child.animate().cancel();
        reset(child);
        Context context = child.getContext();
        if (!isEnabled(context)) return;

        String style = getStyle(context, "smart-animation-scroll", "classic");
        if ("none".equals(style)) return;

        child.setAlpha(0f);
        switch (style) {
            case "focus":
                child.setScaleX(0.86f);
                child.setScaleY(0.86f);
                break;
            case "depth":
                child.setScaleX(0.90f);
                child.setScaleY(0.82f);
                child.setTranslationY(dp(child, 42));
                child.setRotationX(7f);
                break;
            case "wave":
                child.setTranslationX(dp(child, (index & 1) == 0 ? 58 : -58));
                child.setRotation((index & 1) == 0 ? 2.5f : -2.5f);
                break;
            case "slide":
                child.setTranslationX(dp(child, 76));
                break;
            case "stack":
                child.setScaleX(0.92f);
                child.setScaleY(0.88f);
                child.setTranslationY(dp(child, 58));
                break;
            case "zoom":
                child.setScaleX(0.66f);
                child.setScaleY(0.66f);
                break;
            case "tilt":
                child.setTranslationX(dp(child, (index & 1) == 0 ? 42 : -42));
                child.setRotationY((index & 1) == 0 ? 12f : -12f);
                break;
            case "cascade":
                child.setTranslationY(dp(child, 52));
                child.setScaleX(0.94f);
                child.setScaleY(0.94f);
                break;
            case "flip":
                child.setRotationX((index & 1) == 0 ? 24f : -24f);
                child.setScaleY(0.82f);
                break;
            case "helix":
                child.setTranslationX(dp(child, (index & 1) == 0 ? 54 : -54));
                child.setRotationY((index & 1) == 0 ? 30f : -30f);
                child.setScaleX(0.82f);
                break;
            case "fan":
                child.setTranslationX(dp(child, (index & 1) == 0 ? 46 : -46));
                child.setRotation((index & 1) == 0 ? 10f : -10f);
                child.setScaleX(0.9f);
                break;
            case "bounce":
                child.setTranslationY(dp(child, 46));
                child.setScaleX(1.12f);
                child.setScaleY(0.72f);
                break;
            case "classic":
            default:
                child.setTranslationY(dp(child, 34));
                child.setScaleX(0.96f);
                child.setScaleY(0.96f);
                break;
        }

        long duration = Math.max(120L, duration(context));
        long delay = Math.min(220L, Math.max(0, index) * ("cascade".equals(style) ? 38L : 24L));
        child.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .rotation(0f)
                .rotationX(0f)
                .rotationY(0f)
                .setStartDelay(delay)
                .setDuration(duration)
                .setInterpolator(("depth".equals(style) || "stack".equals(style)
                        || "bounce".equals(style))
                        ? new OvershootInterpolator(0.7f)
                        : new DecelerateInterpolator())
                .start();
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
