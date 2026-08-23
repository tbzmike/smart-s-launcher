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
 * The exact tapped tile is snapshotted, the real source is temporarily hidden, then the snapshot
 * performs a complete visible 3D flip before it expands to the launcher window. The target app is
 * not started until the flip and expansion have both completed, so Android cannot replace the
 * launcher before the user sees the transition.
 */
public final class LaunchMorphTransition {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private LaunchMorphTransition() {}

    /**
     * Runs a complete left/right 3D flip followed by a full-window expansion.
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
        float sourceAlpha = source.getAlpha();

        FrameLayout overlay = new FrameLayout(context);
        overlay.setClickable(true);
        overlay.setFocusable(false);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        overlay.setCameraDistance(14_000f * context.getResources().getDisplayMetrics().density);

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

        // The source must disappear once its identical overlay exists. Otherwise the unchanged real
        // tile remains visible behind the edge-on snapshot and visually masks the flip.
        source.setAlpha(0f);

        float sourceCenterX = startX + startWidth / 2f;
        boolean leftToRight = sourceCenterX <= host.getWidth() / 2f;
        overlay.setPivotX(leftToRight ? 0f : startWidth);
        overlay.setPivotY(startHeight / 2f);
        float frontEdge = leftToRight ? 90f : -90f;
        float backEdge = -frontEdge;

        // Deliberately slower than the previous transition: each half of the flip is independently
        // visible before any scaling starts.
        long base = Math.max(140L, SmartAnimationEngine.duration(context));
        long flipHalf = Math.max(145L, Math.min(220L, base));
        long backHold = 55L;
        long expandDuration = Math.max(210L, Math.min(330L, base + 100L));

        AtomicBoolean launched = new AtomicBoolean(false);
        Runnable launchOnce = () -> {
            if (!launched.compareAndSet(false, true)) return;
            try {
                launchAction.run();
            } catch (RuntimeException error) {
                cleanup(host, overlay, source, sourceAlpha, snapshot);
                throw error;
            }
        };

        ObjectAnimator flipFront = ObjectAnimator.ofFloat(
                overlay, View.ROTATION_Y, 0f, frontEdge);
        flipFront.setDuration(flipHalf);
        flipFront.setInterpolator(new AccelerateDecelerateInterpolator());

        flipFront.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (overlay.getParent() == null) {
                    restoreSource(source, sourceAlpha);
                    recycle(snapshot);
                    RUNNING.set(false);
                    launchOnce.run();
                    return;
                }

                // At the edge, swap to a clearly different reverse surface and begin the second
                // half from the opposite 90-degree edge. Together these phases read as a full 180°
                // physical card turn rather than a brief tilt.
                GradientDrawable back = new GradientDrawable();
                back.setColor(Color.rgb(20, 20, 24));
                back.setCornerRadius(Math.min(startWidth, startHeight) * 0.12f);
                overlay.setBackground(back);
                face.setColorFilter(Color.argb(78, 210, 225, 255));
                face.setAlpha(0.82f);
                overlay.setRotationY(backEdge);

                ObjectAnimator flipBack = ObjectAnimator.ofFloat(
                        overlay, View.ROTATION_Y, backEdge, 0f);
                flipBack.setDuration(flipHalf);
                flipBack.setInterpolator(new AccelerateDecelerateInterpolator());
                flipBack.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (overlay.getParent() == null) {
                            restoreSource(source, sourceAlpha);
                            recycle(snapshot);
                            RUNNING.set(false);
                            launchOnce.run();
                            return;
                        }
                        // Give the completed back face a short readable beat before morphing it.
                        overlay.postDelayed(() -> startExpansion(
                                host, overlay, source, sourceAlpha, snapshot, face,
                                startX, startY, startWidth, startHeight,
                                expandDuration, launchOnce), backHold);
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        launchOnce.run();
                        cleanup(host, overlay, source, sourceAlpha, snapshot);
                    }
                });
                flipBack.start();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                launchOnce.run();
                cleanup(host, overlay, source, sourceAlpha, snapshot);
            }
        });

        flipFront.start();
        return true;
    }

    private static void startExpansion(ViewGroup host,
                                       FrameLayout overlay,
                                       View source,
                                       float sourceAlpha,
                                       Bitmap snapshot,
                                       ImageView face,
                                       float startX,
                                       float startY,
                                       int startWidth,
                                       int startHeight,
                                       long expandDuration,
                                       Runnable launchOnce) {
        if (overlay.getParent() == null) {
            cleanup(host, overlay, source, sourceAlpha, snapshot);
            launchOnce.run();
            return;
        }

        float targetCenterX = host.getWidth() / 2f;
        float targetCenterY = host.getHeight() / 2f;
        float startCenterX = startX + startWidth / 2f;
        float startCenterY = startY + startHeight / 2f;
        float scaleX = host.getWidth() / (float) startWidth;
        float scaleY = host.getHeight() / (float) startHeight;

        overlay.setPivotX(startWidth / 2f);
        overlay.setPivotY(startHeight / 2f);
        overlay.setRotationY(0f);

        AnimatorSet expand = new AnimatorSet();
        expand.playTogether(
                ObjectAnimator.ofFloat(overlay, View.SCALE_X, 1f, scaleX),
                ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 1f, scaleY),
                ObjectAnimator.ofFloat(overlay, View.TRANSLATION_X,
                        0f, targetCenterX - startCenterX),
                ObjectAnimator.ofFloat(overlay, View.TRANSLATION_Y,
                        0f, targetCenterY - startCenterY),
                ObjectAnimator.ofFloat(face, View.ALPHA, face.getAlpha(), 0.94f)
        );
        expand.setDuration(expandDuration);
        expand.setInterpolator(new DecelerateInterpolator(1.25f));
        expand.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Critical sequencing rule: Android is asked to open the target only after the
                // complete live flip and full-screen morph have visibly finished.
                launchOnce.run();
                overlay.postDelayed(
                        () -> cleanup(host, overlay, source, sourceAlpha, snapshot), 420L);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                launchOnce.run();
                cleanup(host, overlay, source, sourceAlpha, snapshot);
            }
        });
        expand.start();
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

    private static void cleanup(ViewGroup host, View overlay, View source,
                                float sourceAlpha, Bitmap bitmap) {
        try {
            if (overlay.getParent() == host) host.removeView(overlay);
        } catch (RuntimeException ignored) { }
        restoreSource(source, sourceAlpha);
        recycle(bitmap);
        RUNNING.set(false);
    }

    private static void restoreSource(View source, float alpha) {
        try {
            if (source != null) source.setAlpha(alpha);
        } catch (RuntimeException ignored) { }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
