package fr.neamar.kiss;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import fr.neamar.kiss.ui.SmartWorkspaceController;
import fr.neamar.kiss.ui.WorkspacePaneAssignments;

/**
 * Isolated settings screen for the flexible workspace.
 * Keeping this screen static avoids mutating the Smart Features preference hierarchy while
 * AndroidX Preference is navigating it.
 */
public class WorkspaceSettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private SharedPreferences prefs;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        migrateLegacyAssignments();
        setPreferencesFromResource(R.xml.preferences_workspace, null);
        installPaneSwapListeners();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (prefs != null) prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key != null && key.startsWith("smart-workspace-")) {
            sharedPreferences.edit().putBoolean("require-layout-update", true).apply();
        }
    }

    private void migrateLegacyAssignments() {
        if (prefs.getBoolean(WorkspacePaneAssignments.PREF_ASSIGNMENTS_MIGRATED, false)) return;
        boolean widgetsFirst = "widgets".equals(
                prefs.getString(SmartWorkspaceController.PREF_PRIMARY_CONTENT, "history"));
        SharedPreferences.Editor editor = prefs.edit();
        if (!prefs.contains(WorkspacePaneAssignments.PREF_TWO_PANE_HISTORY_POSITION)) {
            editor.putString(WorkspacePaneAssignments.PREF_TWO_PANE_HISTORY_POSITION,
                    widgetsFirst ? "2" : "1");
        }
        if (!prefs.contains(WorkspacePaneAssignments.PREF_FOUR_PANE_HISTORY_POSITION)) {
            editor.putString(WorkspacePaneAssignments.PREF_FOUR_PANE_HISTORY_POSITION,
                    widgetsFirst ? "2" : "1");
        }
        if (!prefs.contains(WorkspacePaneAssignments.PREF_FOUR_PANE_WIDGETS_POSITION)) {
            editor.putString(WorkspacePaneAssignments.PREF_FOUR_PANE_WIDGETS_POSITION,
                    widgetsFirst ? "1" : "2");
        }
        editor.putBoolean(WorkspacePaneAssignments.PREF_ASSIGNMENTS_MIGRATED, true).apply();
    }

    private void installPaneSwapListeners() {
        ListPreference history = findPreference(
                WorkspacePaneAssignments.PREF_FOUR_PANE_HISTORY_POSITION);
        ListPreference widgets = findPreference(
                WorkspacePaneAssignments.PREF_FOUR_PANE_WIDGETS_POSITION);
        if (history == null || widgets == null) return;

        if (history.getValue() != null && history.getValue().equals(widgets.getValue())) {
            widgets.setValue(Integer.toString(WorkspacePaneAssignments.firstPositionExcept(
                    4, parsePosition(history.getValue(), 1))));
        }
        history.setOnPreferenceChangeListener((preference, newValue) -> {
            String next = String.valueOf(newValue);
            if (next.equals(widgets.getValue())) widgets.setValue(history.getValue());
            return true;
        });
        widgets.setOnPreferenceChangeListener((preference, newValue) -> {
            String next = String.valueOf(newValue);
            if (next.equals(history.getValue())) history.setValue(widgets.getValue());
            return true;
        });
    }

    private static int parsePosition(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
