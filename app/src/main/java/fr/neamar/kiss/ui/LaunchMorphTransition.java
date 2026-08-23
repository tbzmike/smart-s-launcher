package fr.neamar.kiss.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Launcher-owned app launch transition.
 *
 * A snapshot of the exact tapped tile is flipped in 3D, turned to its reverse face and then
 * expanded from its real bounds to the launcher window. The real launch is handed off during the
 * expansion so Android can replace the launcher window without mutating the underlying tile.
 */
public final class LaunchMorphTransition {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private LaunchMorphTransition() {}

    /**
     * Runs a left/right 3D flip followed by a full-window expansion.
     * Returns false when the transition cannot safely be constructed; callers must launch directly.
     */
    public static boolean start(Context context, View source, Runnable launchAction) {
        if (context == null || source == null || launchAction == null) return false;
        if (!SmartAnimationEngine.isEnabled(context) || !source.isShown()
                || source.getWidth() <= 1 || source.getHeight() <= 1) return false;
        if (!RUNNING.compareAndSet(false, true)) return true;

        Activity activity = findActivity(context);
        if (activity == null || activity.isFinishing()) {
            RUNNING.set(false);
            return false;
        }
        Window window = activity.getWindow();
        if (window == null) {
            RUNNING.set(false);
            return false;
        }
        View decor = window.getDecorView();
        if (!(decor instanceof ViewGroup) || decor.getWidth() <= 1 || decor.getHeight() <= 1) {
            RUNNING.set(false);
            return false;
        }

        Bitmap snapshot;
        try {
            snapshot = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(snapshot);
            source.draw(canvas);
        } catch (RuntimeException | OutOfMemoryError error) {
            RUNNING.set(false);
            return false;
        }

        ViewGroup host = (ViewGroup) decor;
        int[] sourceLocation = new int[2];
        int[] hostLocation = new int[2];
        source.getLocationOnScreen(sourceLocation);
        host.getLocationOnScreen(hostLocation);

        float startX = sourceLocation[0] - hostLocation[0];
        float startY = sourceLocation[1] - hostLocation[1];
        int startWidth = source.getWidth();
        int startHeight = source.getHeight();

        FrameLayout overlay = new FrameLayout(context);
        overlay.setClickable(true);
        overlay.setFocusable(false);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        overlay.setCameraDistance(18_000f * context.getResources().getDisplayMetrics().density);

        ImageView face = new ImageView(context);
        face.setImageBitmap(snapshot);
        face.setScaleType(ImageView.ScaleType.FIT_XY);
        overlay.addView(face, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(startWidth, startHeight);
        lp.leftMargin = Math.round(startX);
        lp.topMargin = Math.round(startY);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            host.addView(overlay, lp);
        } catch (RuntimeException error) {
            recycle(snapshot);
            RUNNING.set(false);
            return false;
        }

        // Pivot from the outer edge toward the centre: left-side tiles flip left -> right,
        // right-side tiles flip right -> left.
        float sourceCenterX = startX + startWidth / 2f;
        boolean leftToRight = sourceCenterX <= host.getWidth() / 2f;
        overlay.setPivotX(leftToRight ? 0f : startWidth);
        overlay.setPivotY(startHeight / 2f);
        float firstHalfRotation = leftToRight ? 90f : -90f;
        float secondHalfStart = -firstHalfRotation;

        long base = Math.max(110L, SmartAnimationEngine.duration(context));
        long flipHalf = Math.max(75L, Math.min(130L, base * 2 / 3));
        long expandDuration = Math.max(150L, Math.min(250L, base + 70L));

        AtomicBoolean launched = new AtomicBoolean(false);
        Runnable launchOnce = () -> {
            if (!launched.compareAndSet(false, true)) return;
            try {
                launchAction.run();
            } catch (RuntimeException error) {
                cleanup(host, overlay, snapshot);
                throw error;
            }
        };

        ObjectAnimator flipOut = ObjectAnimator.ofFloat(overlay, View.ROTATION_Y, 0f, firstHalfRotation);
        flipOut.setDuration(flipHalf);
        flipOut.setInterpolator(new AccelerateDecelerateInterpolator());

        flipOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (overlay.getParent() == null) {
                    recycle(snapshot);
                    RUNNING.set(false);
                    launchOnce.run();
                    return;
                }

                // Reverse face: retain the tile identity but alter the surface so the midpoint
                // visibly reads as the back of the card before it becomes the launch surface.
                face.setColorFilter(Color.argb(58, 255, 255, 255));
                GradientDrawable back = new GradientDrawable();
                back.setColor(Color.rgb(18, 18, 20));
                back.setCornerRadius(Math.min(startWidth, startHeight) * 0.12f);
                overlay.setBackground(back);
                overlay.setRotationY(secondHalfStart);

                float targetCenterX = host.getWidth() / 2f;
                float targetCenterY = host.getHeight() / 2f;
                float startCenterX = startX + startWidth / 2f;
                float startCenterY = startY + startHeight / 2f;
                float scaleX = host.getWidth() / (float) startWidth;
                float scaleY = host.getHeight() / (float) startHeight;

                overlay.setPivotX(startWidth / 2f);
                overlay.setPivotY(startHeight / 2f);

                AnimatorSet expand = new AnimatorSet();
                expand.playTogether(
                        ObjectAnimator.ofFloat(overlay, View.ROTATION_Y, secondHalfStart, 0f),
                        ObjectAnimator.ofFloat(overlay, View.SCALE_X, 1f, scaleX),
                        ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 1f, scaleY),
                        ObjectAnimator.ofFloat(overlay, View.TRANSLATION_X, 0f, targetCenterX - startCenterX),
                        ObjectAnimator.ofFloat(overlay, View.TRANSLATION_Y, 0f, targetCenterY - startCenterY)
                );
                expand.setDuration(expandDuration);
                expand.setInterpolator(new DecelerateInterpolator(1.35f));

                // Handoff happens during expansion: the morph remains visible while Android starts
                // the target app, then the real app window naturally replaces the launcher.
                new Handler(Looper.getMainLooper()).postDelayed(
                        launchOnce, Math.max(80L, expandDuration * 58L / 100L));

                expand.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        launchOnce.run();
                        overlay.postDelayed(() -> cleanup(host, overlay, snapshot), 220L);
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        launchOnce.run();
                        cleanup(host, overlay, snapshot);
                    }
                });
                expand.start();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                launchOnce.run();
                cleanup(host, overlay, snapshot);
            }
        });

        flipOut.start();
        return true;
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof android.content.ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context base = ((android.content.ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private static void cleanup(ViewGroup host, View overlay, Bitmap bitmap) {
        try {
            if (overlay.getParent() == host) host.removeView(overlay);
        } catch (RuntimeException ignored) { }
        recycle(bitmap);
        RUNNING.set(false);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
