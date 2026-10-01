package fr.neamar.kiss.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

/**
 * Single source of truth for Smart S frozen/disabled-app settings.
 *
 * These settings used to be read independently by AppProvider, shortcuts, History and favorites,
 * which allowed one surface to honor a toggle while another silently ignored it.
 */
public final class FrozenAppPreferences {
    public static final String PREF_DETECT = "smart-detect-frozen-apps";
    public static final String PREF_KEEP_SEARCHABLE = "smart-keep-frozen-searchable";
    public static final String PREF_GREY = "smart-grey-frozen-apps";
    public static final String PREF_AUTO_ENABLE = "smart-auto-enable-frozen-apps";
    public static final String PREF_PACKAGE_MONITORING = "smart-package-change-monitoring";
    public static final String PREF_RECONCILE_INTERVAL = "smart-frozen-refresh-interval";
    public static final String PREF_KEEP_HISTORY = "smart-keep-frozen-history";

    private static final String LEGACY_INDEX_DISABLED = "index-disabled-apps";

    private FrozenAppPreferences() { }

    @NonNull
    public static SharedPreferences prefs(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean detect(@NonNull Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(PREF_DETECT)) return prefs.getBoolean(PREF_DETECT, true);
        return prefs.getBoolean(LEGACY_INDEX_DISABLED, true);
    }

    public static boolean keepSearchable(@NonNull Context context) {
        return detect(context) && prefs(context).getBoolean(PREF_KEEP_SEARCHABLE, true);
    }

    public static boolean grey(@NonNull Context context) {
        return detect(context) && prefs(context).getBoolean(PREF_GREY, true);
    }

    public static boolean autoEnable(@NonNull Context context) {
        return detect(context) && prefs(context).getBoolean(PREF_AUTO_ENABLE, true);
    }

    public static boolean monitorPackageChanges(@NonNull Context context) {
        return prefs(context).getBoolean(PREF_PACKAGE_MONITORING, true);
    }

    public static boolean keepHistoryAndFavorites(@NonNull Context context) {
        return detect(context) && prefs(context).getBoolean(PREF_KEEP_HISTORY, true);
    }

    /**
     * Returns -1 for "Package changes only"; otherwise the configured fallback interval.
     */
    public static long reconcileDelayMs(@NonNull Context context) {
        String value = prefs(context).getString(PREF_RECONCILE_INTERVAL, "15");
        if (value == null || "package-only".equals(value)) return -1L;
        try {
            long seconds = Long.parseLong(value);
            return Math.max(15L, Math.min(300L, seconds)) * 1000L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
