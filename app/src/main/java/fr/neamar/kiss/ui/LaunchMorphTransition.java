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

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** Launcher-owned tile-to-window app launch transition. */
public final class LaunchMorphTransition {
    public static final String PREF_LAUNCH_STYLE = "smart-history-launch-animation";
    public static final String STYLE_FLIP = "flip";
    public static final String STYLE_BACKSPIN = "backspin";
    public static final String STYLE_RANDOM = "random";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final String PREF_FLIP_SPEED = "smart-launch-flip-speed";
    private static final float DEFAULT_FLIP_SPEED = 0.85f;

    private LaunchMorphTransition() {}

    /**
     * Snapshots the tapped history tile, performs the selected 3D launch animation, expands the
     * tile to the full launcher window, then invokes the actual launch action. Random resolves to
     * one of the real animations independently for every tap.
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
        source.setAlpha(0f);

        long base = Math.max(140L, SmartAnimationEngine.duration(context));
        long rawFlipHalf = Math.max(145L, Math.min(220L, base));
        float flipSpeed = readFlipSpeed(context);
        long flipHalf = Math.max(120L, Math.min(380L, Math.round(rawFlipHalf / flipSpeed)));
        long backHold = 60L;
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

        String style = resolveLaunchStyle(context);
        if (STYLE_BACKSPIN.equals(style)) {
            startBackspin(host, overlay, face, source, sourceAlpha, snapshot,
                    startX, startY, startWidth, startHeight,
                    flipHalf, backHold, expandDuration, launchOnce);
        } else {
            startFlip(host, overlay, face, source, sourceAlpha, snapshot,
                    startX, startY, startWidth, startHeight,
                    flipHalf, backHold, expandDuration, launchOnce);
        }
        return true;
    }

    static String resolveLaunchStyle(Context context) {
        String configured = SmartAnimationEngine.getStyle(context, PREF_LAUNCH_STYLE, STYLE_FLIP);
        if (STYLE_RANDOM.equals(configured)) {
            return ThreadLocalRandom.current().nextBoolean() ? STYLE_FLIP : STYLE_BACKSPIN;
        }
        return STYLE_BACKSPIN.equals(configured) ? STYLE_BACKSPIN : STYLE_FLIP;
    }

    private static void startFlip(ViewGroup host, FrameLayout overlay, ImageView face,
                                  View source, float sourceAlpha, Bitmap snapshot,
                                  float startX, float startY, int startWidth, int startHeight,
                                  long flipHalf, long backHold, long expandDuration,
                                  Runnable launchOnce) {
        float sourceCenterX = startX + startWidth / 2f;
        boolean leftToRight = sourceCenterX <= host.getWidth() / 2f;
        overlay.setPivotX(leftToRight ? 0f : startWidth);
        overlay.setPivotY(startHeight / 2f);
        float frontEdge = leftToRight ? 90f : -90f;
        float backEdge = -frontEdge;

        ObjectAnimator flipFront = ObjectAnimator.ofFloat(overlay, View.ROTATION_Y, 0f, frontEdge);
        flipFront.setDuration(flipHalf);
        flipFront.setInterpolator(new AccelerateDecelerateInterpolator());
        flipFront.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (overlay.getParent() == null) {
                    finishAborted(source, sourceAlpha, snapshot, launchOnce);
                    return;
                }
                GradientDrawable back = createBackSurface(startWidth, startHeight);
                overlay.setBackground(back);
                face.setColorFilter(Color.argb(78, 210, 225, 255));
                face.setAlpha(0.82f);
                overlay.setRotationY(backEdge);

                ObjectAnimator flipBack = ObjectAnimator.ofFloat(overlay, View.ROTATION_Y, backEdge, 0f);
                flipBack.setDuration(flipHalf);
                flipBack.setInterpolator(new AccelerateDecelerateInterpolator());
                flipBack.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (overlay.getParent() == null) {
                            finishAborted(source, sourceAlpha, snapshot, launchOnce);
                            return;
                        }
                        overlay.postDelayed(() -> startExpansion(host, overlay, source,
                                sourceAlpha, snapshot, face, startX, startY, startWidth,
                                startHeight, expandDuration, launchOnce), backHold);
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
    }

    /**
     * New secondary animation: the tile begins upside down on its rear plane, spins forward through
     * a complete 3D turn, rights itself, then expands to fill the screen before launch.
     */
    private static void startBackspin(ViewGroup host, FrameLayout overlay, ImageView face,
                                      View source, float sourceAlpha, Bitmap snapshot,
                                      float startX, float startY, int startWidth, int startHeight,
                                      long flipHalf, long backHold, long expandDuration,
                                      Runnable launchOnce) {
        overlay.setPivotX(startWidth / 2f);
        overlay.setPivotY(startHeight / 2f);
        overlay.setBackground(createBackSurface(startWidth, startHeight));
        overlay.setRotationX(180f);
        overlay.setRotation(180f);
        overlay.setScaleX(0.76f);
        overlay.setScaleY(0.76f);
        face.setColorFilter(Color.argb(88, 205, 220, 255));
        face.setAlpha(0.76f);

        AnimatorSet spin = new AnimatorSet();
        spin.playTogether(
                ObjectAnimator.ofFloat(overlay, View.ROTATION_X, 180f, 360f),
                ObjectAnimator.ofFloat(overlay, View.ROTATION, 180f, 360f),
                ObjectAnimator.ofFloat(overlay, View.SCALE_X, 0.76f, 1f),
                ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 0.76f, 1f),
                ObjectAnimator.ofFloat(face, View.ALPHA, 0.76f, 0.96f)
        );
        spin.setDuration(Math.max(260L, flipHalf * 2));
        spin.setInterpolator(new AccelerateDecelerateInterpolator());
        spin.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (overlay.getParent() == null) {
                    finishAborted(source, sourceAlpha, snapshot, launchOnce);
                    return;
                }
                overlay.setRotationX(0f);
                overlay.setRotation(0f);
                overlay.setBackground(null);
                face.clearColorFilter();
                overlay.postDelayed(() -> startExpansion(host, overlay, source,
                        sourceAlpha, snapshot, face, startX, startY, startWidth,
                        startHeight, expandDuration, launchOnce), backHold);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                launchOnce.run();
                cleanup(host, overlay, source, sourceAlpha, snapshot);
            }
        });
        spin.start();
    }

    private static GradientDrawable createBackSurface(int width, int height) {
        GradientDrawable back = new GradientDrawable();
        back.setColor(Color.rgb(20, 20, 24));
        back.setCornerRadius(Math.min(width, height) * 0.12f);
        return back;
    }

    private static void finishAborted(View source, float sourceAlpha, Bitmap snapshot,
                                      Runnable launchOnce) {
        restoreSource(source, sourceAlpha);
        recycle(snapshot);
        RUNNING.set(false);
        launchOnce.run();
    }

    private static float readFlipSpeed(Context context) {
        String raw = SmartAnimationEngine.getStyle(
                context, PREF_FLIP_SPEED, Float.toString(DEFAULT_FLIP_SPEED));
        try {
            float value = Float.parseFloat(raw);
            return Math.max(0.45f, Math.min(1.60f, value));
        } catch (NumberFormatException ignored) {
            return DEFAULT_FLIP_SPEED;
        }
    }

    private static void startExpansion(ViewGroup host, FrameLayout overlay, View source,
                                       float sourceAlpha, Bitmap snapshot, ImageView face,
                                       float startX, float startY, int startWidth, int startHeight,
                                       long expandDuration, Runnable launchOnce) {
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
        overlay.setRotation(0f);
        overlay.setRotationX(0f);
        overlay.setRotationY(0f);
        overlay.setScaleX(1f);
        overlay.setScaleY(1f);
        face.clearColorFilter();

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
                launchOnce.run();
                overlay.postDelayed(() -> cleanup(
                        host, overlay, source, sourceAlpha, snapshot), 420L);
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
