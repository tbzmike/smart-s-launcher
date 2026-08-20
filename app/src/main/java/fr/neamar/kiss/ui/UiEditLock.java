package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

/** Central policy for launcher/home-screen edit protection. */
public final class UiEditLock {
    public static final String PREF_LOCKED = "smart-ui-locked";

    private UiEditLock() {}

    public static boolean isLocked(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_LOCKED, false);
    }

    public static boolean allowEdit(Context context) {
        if (!isLocked(context)) return true;
        Toast.makeText(context, "Launcher UI is locked. Unlock it in Settings to make changes.",
                Toast.LENGTH_SHORT).show();
        return false;
    }

    /**
     * Keep workspace edit entry points disabled while the UI lock is active, and restore the
     * user's previous values when the lock is released.
     */
    public static void syncWorkspaceEditState(Context context, boolean locked) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        if (locked) {
            if (!prefs.contains("smart-ui-lock-backup-free-widget-resize")) {
                editor.putBoolean("smart-ui-lock-backup-free-widget-resize",
                        prefs.getBoolean("smart-workspace-free-widget-resize", true));
            }
            if (!prefs.contains("smart-ui-lock-backup-empty-add-widget")) {
                editor.putBoolean("smart-ui-lock-backup-empty-add-widget",
                        prefs.getBoolean("smart-workspace-empty-add-widget", true));
            }
            editor.putBoolean("smart-workspace-free-widget-resize", false);
            editor.putBoolean("smart-workspace-empty-add-widget", false);
        } else {
            if (prefs.contains("smart-ui-lock-backup-free-widget-resize")) {
                editor.putBoolean("smart-workspace-free-widget-resize",
                        prefs.getBoolean("smart-ui-lock-backup-free-widget-resize", true));
                editor.remove("smart-ui-lock-backup-free-widget-resize");
            }
            if (prefs.contains("smart-ui-lock-backup-empty-add-widget")) {
                editor.putBoolean("smart-workspace-empty-add-widget",
                        prefs.getBoolean("smart-ui-lock-backup-empty-add-widget", true));
                editor.remove("smart-ui-lock-backup-empty-add-widget");
            }
        }
        editor.apply();
    }
}
