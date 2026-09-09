package fr.neamar.kiss.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.Map;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.UIColors;

/**
 * Small process-local cache used to bridge short-lived Result objects.
 *
 * AppResult objects are rebuilt whenever history/search is refreshed, while the launcher icon
 * itself normally has not changed. IconsHandler already has a persistent PNG cache, but decoding
 * that PNG asynchronously still creates visible icon pop-in on Home return. Keeping only drawable
 * ConstantState objects here lets a new AppResult bind immediately without sharing mutable Drawable
 * instances between ImageViews.
 *
 * Themed icons are safe to keep here as long as the dynamic palette is part of the cache identity.
 * A wallpaper/system-color change therefore creates a different key automatically instead of
 * disabling the whole warm cache during ordinary scrolling.
 */
public final class AppIconMemoryCache {
    private static final int MAX_ENTRIES = 128;
    private static final LruCache<String, Drawable.ConstantState> CACHE =
            new LruCache<>(MAX_ENTRIES);

    private AppIconMemoryCache() { }

    @Nullable
    public static Drawable get(@NonNull Context context, @NonNull String componentId) {
        Drawable.ConstantState state;
        synchronized (CACHE) {
            state = CACHE.get(cacheKey(context, componentId));
        }
        return state == null ? null : state.newDrawable(context.getResources());
    }

    public static void put(@NonNull Context context, @NonNull String componentId,
                           @Nullable Drawable drawable) {
        if (drawable == null) return;
        Drawable.ConstantState state = drawable.getConstantState();
        if (state == null) return;
        synchronized (CACHE) {
            CACHE.put(cacheKey(context, componentId), state);
        }
    }

    /** Remove every visual variant for one component after an explicit icon-affecting change. */
    public static void invalidate(@Nullable String componentId) {
        if (componentId == null) return;
        String prefix = componentId + '|';
        synchronized (CACHE) {
            for (Map.Entry<String, Drawable.ConstantState> entry : CACHE.snapshot().entrySet()) {
                if (entry.getKey().startsWith(prefix)) CACHE.remove(entry.getKey());
            }
        }
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.evictAll();
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
