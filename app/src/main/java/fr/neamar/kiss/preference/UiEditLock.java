package fr.neamar.kiss.preference;

import android.content.Context;
import android.content.SharedPreferences;

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

    private UiEditLock() {
    }

    public static boolean isLocked(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_KEY, false);
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
        updateSummary(toggle, locked);
        toggle.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean newLocked = Boolean.TRUE.equals(newValue);
            updateSummary(toggle, newLocked);
            applyLockState(root, newLocked);
            return true;
        });
        applyLockState(root, locked);
    }

    /** Re-applies locking after a screen dynamically adds more UI preferences. */
    public static void refresh(@NonNull Context context, @NonNull PreferenceGroup root) {
        applyLockState(root, isLocked(context));
        Preference pref = root.findPreference(PREF_KEY);
        if (pref instanceof SwitchPreference) {
            updateSummary((SwitchPreference) pref, isLocked(context));
        }
    }

    private static void updateSummary(@NonNull SwitchPreference toggle, boolean locked) {
        toggle.setSummary(locked
                ? "Locked — layout, appearance, animation and direct resize editing are disabled."
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
                // Restore descendants when unlocking. Their own dependency rules can still
                // disable individual controls afterwards through AndroidX Preference.
                applyLockState((PreferenceGroup) child, false);
            }
        }
    }
}
