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

/**
 * Small process-local cache used to bridge short-lived Result objects.
 *
 * AppResult objects are rebuilt whenever history/search is refreshed, while the launcher icon
 * itself normally has not changed. IconsHandler already has a persistent PNG cache, but decoding
 * that PNG asynchronously still creates visible icon pop-in on Home return. Keeping only drawable
 * ConstantState objects here lets a new AppResult bind immediately without sharing mutable Drawable
 * instances between ImageViews.
 */
public final class AppIconMemoryCache {
    private static final int MAX_ENTRIES = 128;
    private static final LruCache<String, Drawable.ConstantState> CACHE =
            new LruCache<>(MAX_ENTRIES);

    private AppIconMemoryCache() { }

    @Nullable
    public static Drawable get(@NonNull Context context, @NonNull String componentId) {
        if (!isCacheable(context)) return null;
        Drawable.ConstantState state;
        synchronized (CACHE) {
            state = CACHE.get(cacheKey(context, componentId));
        }
        return state == null ? null : state.newDrawable(context.getResources());
    }

    public static void put(@NonNull Context context, @NonNull String componentId,
                           @Nullable Drawable drawable) {
        if (drawable == null || !isCacheable(context)) return;
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

    private static boolean isCacheable(Context context) {
        // System-color themed icons are intentionally dynamic in IconsHandler too. Do not retain a
        // process-local snapshot that could outlive a wallpaper/material-color change.
        return !(DrawableUtils.hasThemedIcons() && DrawableUtils.isThemedIconEnabled(context));
    }

    private static String cacheKey(Context context, String componentId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String pack = prefs.getString("icons-pack", "default");
        String shape = prefs.getString("adaptive-shape", "0");
        boolean forceAdaptive = prefs.getBoolean("force-adaptive", true);
        boolean forceShape = prefs.getBoolean("force-shape", true);
        int density = context.getResources().getDisplayMetrics().densityDpi;
        String effectivePack = KissApplication.getApplication(context)
                .getIconsHandler().getIconPack().getPackPackageName();
        return componentId + '|' + effectivePack + '|' + pack + '|' + shape + '|'
                + forceAdaptive + '|' + forceShape + '|' + density;
    }
}
