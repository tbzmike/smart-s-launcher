package fr.neamar.kiss.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.UIColors;

/**
 * Process-local icon cache used to bridge short-lived Result objects.
 *
 * The fast path stores Drawable.ConstantState so each ImageView receives its own Drawable instance.
 * Some generated/themed/custom drawables do not expose ConstantState; those are rasterized once on
 * the existing background icon-loading path and kept in a memory-bounded Bitmap cache instead of
 * being regenerated every time Home/search reconstructs a Result object.
 */
public final class AppIconMemoryCache {
    private static final int MAX_STATE_ENTRIES = 384;
    private static final int MAX_BITMAP_KB = 16 * 1024;
    private static final int MAX_RASTER_DP = 112;

    private static final LruCache<String, Drawable.ConstantState> STATE_CACHE =
            new LruCache<>(MAX_STATE_ENTRIES);
    private static final LruCache<String, Bitmap> BITMAP_CACHE =
            new LruCache<String, Bitmap>(MAX_BITMAP_KB) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    if (value == null) return 0;
                    return Math.max(1, value.getAllocationByteCount() / 1024);
                }
            };

    private AppIconMemoryCache() { }

    @Nullable
    public static Drawable get(@NonNull Context context, @NonNull String componentId) {
        String key = cacheKey(context, componentId);
        Drawable.ConstantState state;
        Bitmap bitmap;
        synchronized (STATE_CACHE) {
            state = STATE_CACHE.get(key);
        }
        if (state != null) return state.newDrawable(context.getResources());

        synchronized (BITMAP_CACHE) {
            bitmap = BITMAP_CACHE.get(key);
        }
        return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
    }

    public static void put(@NonNull Context context, @NonNull String componentId,
                           @Nullable Drawable drawable) {
        if (drawable == null) return;
        String key = cacheKey(context, componentId);
        Drawable.ConstantState state = drawable.getConstantState();
        if (state != null) {
            synchronized (STATE_CACHE) {
                STATE_CACHE.put(key, state);
            }
            synchronized (BITMAP_CACHE) {
                BITMAP_CACHE.remove(key);
            }
            return;
        }

        Bitmap bitmap = rasterize(context, drawable);
        if (bitmap == null) return;
        synchronized (BITMAP_CACHE) {
            BITMAP_CACHE.put(key, bitmap);
        }
    }

    /** Remove every visual variant for one component after an explicit icon-affecting change. */
    public static void invalidate(@Nullable String componentId) {
        if (componentId == null) return;
        String prefix = componentId + '|';
        synchronized (STATE_CACHE) {
            for (String key : new HashSet<>(STATE_CACHE.snapshot().keySet())) {
                if (key.startsWith(prefix)) STATE_CACHE.remove(key);
            }
        }
        synchronized (BITMAP_CACHE) {
            for (String key : new HashSet<>(BITMAP_CACHE.snapshot().keySet())) {
                if (key.startsWith(prefix)) BITMAP_CACHE.remove(key);
            }
        }
    }

    public static void clear() {
        synchronized (STATE_CACHE) {
            STATE_CACHE.evictAll();
        }
        synchronized (BITMAP_CACHE) {
            BITMAP_CACHE.evictAll();
        }
    }

    private static Bitmap rasterize(Context context, Drawable drawable) {
        try {
            float density = context.getResources().getDisplayMetrics().density;
            int fallback = Math.max(1, Math.round(72f * density));
            int max = Math.max(fallback, Math.round(MAX_RASTER_DP * density));
            int width = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            if (width <= 0) width = fallback;
            if (height <= 0) height = fallback;
            float scale = Math.min(1f, max / (float) Math.max(width, height));
            width = Math.max(1, Math.round(width * scale));
            height = Math.max(1, Math.round(height * scale));

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            int oldLeft = drawable.getBounds().left;
            int oldTop = drawable.getBounds().top;
            int oldRight = drawable.getBounds().right;
            int oldBottom = drawable.getBounds().bottom;
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            drawable.setBounds(oldLeft, oldTop, oldRight, oldBottom);
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static String cacheKey(Context context, String componentId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String pack = prefs.getString("icons-pack", "default");
        String shape = prefs.getString("adaptive-shape", "0");
        boolean forceAdaptive = prefs.getBoolean("force-adaptive", true);
        boolean forceShape = prefs.getBoolean("force-shape", true);
        boolean themed = DrawableUtils.hasThemedIcons() && DrawableUtils.isThemedIconEnabled(context);
        int themedBackground = 0;
        int themedForeground = 0;
        if (themed) {
            int[] colors = UIColors.getIconColors(context);
            if (colors.length > 0) themedBackground = colors[0];
            if (colors.length > 1) themedForeground = colors[1];
        }
        int density = context.getResources().getDisplayMetrics().densityDpi;
        String effectivePack = KissApplication.getApplication(context)
                .getIconsHandler().getIconPack().getPackPackageName();
        return componentId + '|' + effectivePack + '|' + pack + '|' + shape + '|'
                + forceAdaptive + '|' + forceShape + '|' + themed + '|'
                + themedBackground + '|' + themedForeground + '|' + density;
    }
}
