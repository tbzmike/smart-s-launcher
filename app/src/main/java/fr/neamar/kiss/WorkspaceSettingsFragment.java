package fr.neamar.kiss;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

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
        setPreferencesFromResource(R.xml.preferences_workspace, null);
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
}
