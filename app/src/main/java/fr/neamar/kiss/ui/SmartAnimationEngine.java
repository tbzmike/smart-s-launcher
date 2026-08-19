package fr.neamar.kiss.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Launcher-owned animation helpers. These animations run on Smart S views directly instead of
 * depending on OEM/system window animation policies.
 */
public final class SmartAnimationEngine {
    private SmartAnimationEngine() {}

    private static int shortDuration(Context context) {
        return context.getResources().getInteger(android.R.integer.config_shortAnimTime);
    }

    public static void animateDialogIn(Dialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        decor.animate().cancel();
        decor.setAlpha(0f);
        decor.setScaleX(0.96f);
        decor.setScaleY(0.96f);
        decor.setTranslationY(dp(decor, 10));
        decor.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(shortDuration(decor.getContext()))
                .setInterpolator(new DecelerateInterpolator())
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
        decor.animate()
                .alpha(0f)
                .scaleX(0.97f)
                .scaleY(0.97f)
                .translationY(dp(decor, 8))
                .setDuration(Math.max(100, shortDuration(decor.getContext()) * 3 / 4))
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        decor.animate().setListener(null);
                        if (dialog.isShowing()) dialog.dismiss();
                    }
                })
                .start();
    }

    public static void animateWindowSwitch(View outgoing, View incoming) {
        int duration = shortDuration(incoming.getContext());
        if (outgoing != null) {
            outgoing.animate().cancel();
            outgoing.animate().alpha(0f).translationX(-dp(outgoing, 12)).setDuration(duration / 2).start();
        }
        incoming.animate().cancel();
        incoming.setAlpha(0f);
        incoming.setTranslationX(dp(incoming, 16));
        incoming.animate().alpha(1f).translationX(0f).setDuration(duration)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    public static void animateListMove(View child, int delta, boolean isNew) {
        int duration = shortDuration(child.getContext());
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

    private static float dp(View view, int value) {
        return value * view.getResources().getDisplayMetrics().density;
    }
}
