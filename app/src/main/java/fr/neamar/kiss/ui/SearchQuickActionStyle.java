package fr.neamar.kiss.ui;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.graphics.ColorUtils;

/** Wallpaper-tinted controls with readable labels even over a bright wallpaper. */
public final class SearchQuickActionStyle {
    private static final int FALLBACK_WALLPAPER = 0xFF606064;
    private static final int SURFACE_ALPHA = 224;
    private static volatile Integer cachedSurface;

    private SearchQuickActionStyle() { }

    /** Re-read colours only after the existing wallpaper-change receiver reports a change. */
    public static void invalidateWallpaperColor() {
        cachedSurface = null;
    }

    @ColorInt
    public static int wallpaperSurface(Context context) {
        Integer cached = cachedSurface;
        if (cached != null) return cached;
        int wallpaper = FALLBACK_WALLPAPER;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                WallpaperColors colors = WallpaperManager.getInstance(context.getApplicationContext())
                        .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                if (colors != null && colors.getPrimaryColor() != null) {
                    wallpaper = colors.getPrimaryColor().toArgb();
                }
            } catch (RuntimeException ignored) {
                // Some wallpaper providers do not expose colours; the translucent fallback is safe.
            }
        }
        int surface = surfaceForWallpaperColor(wallpaper);
        cachedSurface = surface;
        return surface;
    }

    /** Bound the lightest possible composited surface so white text has at least 4.5:1 contrast. */
    @ColorInt
    public static int surfaceForWallpaperColor(@ColorInt int wallpaper) {
        int opaqueWallpaper = ColorUtils.setAlphaComponent(wallpaper, 255);
        int tint = ColorUtils.blendARGB(Color.BLACK, opaqueWallpaper, 0.30f);
        return ColorUtils.setAlphaComponent(tint, SURFACE_ALPHA);
    }

    public static void apply(View view, @ColorInt int surface) {
        if (!(view instanceof AppCompatButton)) return;
        AppCompatButton button = (AppCompatButton) view;
        float density = view.getResources().getDisplayMetrics().density;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surface);
        shape.setCornerRadius(24f * density);
        shape.setStroke(Math.max(1, Math.round(density)), 0x66FFFFFF);
        button.setSupportBackgroundTintList(null);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1CFFFFFF), shape, null));
        button.setTextColor(Color.WHITE);
    }
}
