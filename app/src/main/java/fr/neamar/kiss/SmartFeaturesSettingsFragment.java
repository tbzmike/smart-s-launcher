package fr.neamar.kiss;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

/**
 * Isolated preference hierarchy for Smart S extensions.
 *
 * Keeping these preferences separate from the legacy KISS preference tree limits the impact of
 * future Smart S settings changes. When opened from an existing KISS category, only the relevant
 * Smart S section is shown.
 */
public class SmartFeaturesSettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String ARG_SECTION = "smart_section";

    private SharedPreferences prefs;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        setPreferencesFromResource(R.xml.preferences_smart_features, null);
        filterToRequestedSection();
    }

    private void filterToRequestedSection() {
        Bundle args = getArguments();
        String section = args == null ? null : args.getString(ARG_SECTION);
        if (section == null || section.isEmpty()) return;

        PreferenceGroup root = getPreferenceScreen();
        if (root == null) return;

        for (int i = root.getPreferenceCount() - 1; i >= 0; i--) {
            Preference child = root.getPreference(i);
            if (!(child instanceof PreferenceGroup)) continue;

            PreferenceGroup group = (PreferenceGroup) child;
            boolean keep;
            switch (section) {
                case "notifications":
                    keep = group.findPreference("smart-notification-timeline-enabled") != null
                            || group.findPreference("smart-notification-mark-read") != null
                            || group.findPreference("enable-notification-history") != null;
                    break;
                case "frozen":
                    keep = group.findPreference("smart-detect-frozen-apps") != null;
                    break;
                case "animations":
                    keep = group.findPreference("smart-animations-enabled") != null;
                    break;
                case "wallpaper":
                    keep = group.findPreference("smart-focus-blur-enabled") != null;
                    break;
                default:
                    keep = true;
                    break;
            }

            if (!keep) root.removePreference(child);
        }

        switch (section) {
            case "notifications":
                root.setTitle("Smart notifications & history");
                break;
            case "frozen":
                root.setTitle("Frozen apps & app state");
                break;
            case "animations":
                root.setTitle("Smart animations & transitions");
                break;
            case "wallpaper":
                root.setTitle("Smart wallpaper & blur");
                break;
            default:
                break;
        }
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
