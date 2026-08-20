package fr.neamar.kiss.preference;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;

/**
 * Central lock for launcher appearance/layout editing.
 *
 * The same preference is used by Settings and direct on-home editing so the UI cannot be
 * changed from one path while another path is locked.
 */
public final class UiEditLock {
    public static final String PREF_KEY = "smart-ui-locked";

    private static final String BACKUP_WIDGET_RESIZE = "smart-ui-lock-backup-free-widget-resize";
    private static final String BACKUP_EMPTY_ADD_WIDGET = "smart-ui-lock-backup-empty-add-widget";

    private UiEditLock() {
    }

    public static boolean isLocked(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_KEY, false);
    }

    /** Guard a direct home-screen edit action even if a stale popup or gesture is still active. */
    public static boolean allowEdit(@NonNull Context context) {
        if (!isLocked(context)) return true;
        Toast.makeText(context,
                "Launcher UI is locked. Unlock it in Settings to make changes.",
                Toast.LENGTH_SHORT).show();
        return false;
    }

    public static boolean isLockableRoot(@Nullable String rootKey) {
        return "history_category".equals(rootKey)
                || "ui-holder".equals(rootKey)
                || "ux-holder".equals(rootKey)
                || "theme-customisation".equals(rootKey);
    }

    /** Adds the shared lock toggle to this screen and applies the current lock state. */
    public static void install(@NonNull Context context, @NonNull PreferenceGroup root) {
        Preference existing = root.findPreference(PREF_KEY);
        SwitchPreference toggle;
        if (existing instanceof SwitchPreference) {
            toggle = (SwitchPreference) existing;
        } else {
            toggle = new SwitchPreference(context);
            toggle.setKey(PREF_KEY);
            toggle.setTitle("Lock launcher UI");
            toggle.setDefaultValue(false);
            toggle.setOrder(-20000);
            root.addPreference(toggle);
        }

        boolean locked = isLocked(context);
        syncRuntimeState(context, locked);
        updateSummary(toggle, locked);
        toggle.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean newLocked = Boolean.TRUE.equals(newValue);
            syncRuntimeState(context, newLocked);
            updateSummary(toggle, newLocked);
            applyLockState(root, newLocked);
            return true;
        });
        applyLockState(root, locked);
    }

    /** Re-applies locking after a screen dynamically adds more UI preferences. */
    public static void refresh(@NonNull Context context, @NonNull PreferenceGroup root) {
        boolean locked = isLocked(context);
        syncRuntimeState(context, locked);
        applyLockState(root, locked);
        Preference pref = root.findPreference(PREF_KEY);
        if (pref instanceof SwitchPreference) {
            updateSummary((SwitchPreference) pref, locked);
        }
    }

    /** Ensure runtime edit entry points mirror the global lock even before Settings is opened. */
    public static void syncRuntimeState(@NonNull Context context) {
        syncRuntimeState(context, isLocked(context));
    }

    private static void syncRuntimeState(@NonNull Context context, boolean locked) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        if (locked) {
            if (!prefs.contains(BACKUP_WIDGET_RESIZE)) {
                editor.putBoolean(BACKUP_WIDGET_RESIZE,
                        prefs.getBoolean("smart-workspace-free-widget-resize", true));
            }
            if (!prefs.contains(BACKUP_EMPTY_ADD_WIDGET)) {
                editor.putBoolean(BACKUP_EMPTY_ADD_WIDGET,
                        prefs.getBoolean("smart-workspace-empty-add-widget", true));
            }
            editor.putBoolean("smart-workspace-free-widget-resize", false);
            editor.putBoolean("smart-workspace-empty-add-widget", false);
        } else {
            if (prefs.contains(BACKUP_WIDGET_RESIZE)) {
                editor.putBoolean("smart-workspace-free-widget-resize",
                        prefs.getBoolean(BACKUP_WIDGET_RESIZE, true));
                editor.remove(BACKUP_WIDGET_RESIZE);
            }
            if (prefs.contains(BACKUP_EMPTY_ADD_WIDGET)) {
                editor.putBoolean("smart-workspace-empty-add-widget",
                        prefs.getBoolean(BACKUP_EMPTY_ADD_WIDGET, true));
                editor.remove(BACKUP_EMPTY_ADD_WIDGET);
            }
        }
        editor.apply();
    }

    private static void updateSummary(@NonNull SwitchPreference toggle, boolean locked) {
        toggle.setSummary(locked
                ? "Locked — all launcher editing is blocked, including resize, move, remove, uninstall, rename, favorites, tags and widget editing."
                : "Unlocked — launcher UI editing is available.");
    }

    private static void applyLockState(@NonNull PreferenceGroup group, boolean locked) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            String key = child.getKey();
            if (PREF_KEY.equals(key)) {
                child.setEnabled(true);
                continue;
            }
            // Live previews are display-only and remain visible while locked.
            if (key != null && key.startsWith("live-preview-")) {
                child.setEnabled(true);
                continue;
            }

            child.setEnabled(!locked);
            if (child instanceof PreferenceGroup && !locked) {
                applyLockState((PreferenceGroup) child, false);
            }
        }
    }
}
