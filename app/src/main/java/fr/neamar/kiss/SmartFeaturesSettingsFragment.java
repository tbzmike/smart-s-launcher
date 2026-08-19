package fr.neamar.kiss;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

/**
 * Isolated preference hierarchy for Smart S extensions.
 *
 * Keeping these preferences separate from the legacy KISS preference tree limits the impact of
 * future Smart S settings changes and avoids dynamic mutation of the core Settings hierarchy.
 */
public class SmartFeaturesSettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private SharedPreferences prefs;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        // This fragment owns one flat, isolated hierarchy. Ignore the parent preference key passed
        // by SettingsActivity so it can never be resolved against the legacy KISS XML by mistake.
        setPreferencesFromResource(R.xml.preferences_smart_features, null);
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
        if ("enable-notification-history".equals(key)
                && sharedPreferences.getBoolean(key, false)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }
}
