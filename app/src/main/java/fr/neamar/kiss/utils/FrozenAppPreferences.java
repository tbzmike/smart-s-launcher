package fr.neamar.kiss.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;

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
    private static final String PREF_LEGACY_POWER_MIGRATED = "smart-frozen-power-migrated-v330179";

    /**
     * Version 3.30.178 silently defaulted to a full installed-app scan every 15 seconds.
     * Existing preference screens may already have persisted that default, so changing the
     * XML fallback alone does not stop battery-intensive polling for existing installations.
     * Retire that legacy value once, preserving 30/60/300s and "package-only" choices.
     * Users may explicitly select 15 seconds again after migration if they need it.
     */
    public static void migrateLegacyPowerPolling(@NonNull Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.getBoolean(PREF_LEGACY_POWER_MIGRATED, false)) return;
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(PREF_LEGACY_POWER_MIGRATED, true);
        String existing;
        try {
            existing = preferences.getString(PREF_RECONCILE_INTERVAL, "package-only");
        } catch (ClassCastException e) {
            existing = "package-only";
        }
        if ("15".equals(existing)) {
            editor.putString(PREF_RECONCILE_INTERVAL, "package-only");
        }
        editor.apply();
    }

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
     * Apply the frozen-history policy consistently to every launcher surface.
     *
     * IceBox shortcuts are launch routes owned by IceBox, not frozen Smart S app rows. They must
     * remain governed by IceBox's own shortcut behavior instead of being hidden/greyed merely
     * because the wrapped target app is frozen.
     */
    public static boolean keepInHistoryAndFavorites(@NonNull Context context, Pojo pojo) {
        if (pojo == null || !pojo.isDisabled()) return true;
        if (pojo instanceof ShortcutPojo
                && ShortcutUtil.isIceBoxPublisher(context, ((ShortcutPojo) pojo).packageName)) {
            return true;
        }
        return keepHistoryAndFavorites(context);
    }

    /**
     * Returns -1 for "Package changes only"; otherwise the configured fallback interval.
     */
    public static long reconcileDelayMs(@NonNull Context context) {
        String value = prefs(context).getString(PREF_RECONCILE_INTERVAL, "package-only");
        if (value == null || "package-only".equals(value)) return -1L;
        try {
            long seconds = Long.parseLong(value);
            return Math.max(15L, Math.min(300L, seconds)) * 1000L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
