package fr.neamar.kiss;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

/**
 * Adds Smart S configuration entries to the relevant existing KISS settings category.
 * The legacy SettingsFragment remains responsible for loading and managing the category itself.
 */
public class SmartCategorySettingsFragment extends SettingsFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        addSmartSectionEntries(rootKey);
    }

    private void addSmartSectionEntries(@Nullable String rootKey) {
        if ("history_category".equals(rootKey)) {
            addEntry("notifications", "Smart notifications & history",
                    "Timeline, notification actions, persistent history and notification search");
        } else if ("ui-holder".equals(rootKey)) {
            addEntry("wallpaper", "Smart wallpaper & blur",
                    "Smart Focus blur, icon tracking, blur strength and performance");
            addEntry("workspace", "Flexible workspace",
                    "Split the launcher into resizable panes for apps, history, widgets and future Smart S panels");
        } else if ("ux-holder".equals(rootKey)) {
            addEntry("animations", "Smart animations & transitions",
                    "Scrolling, windows, popups, notifications, switching and numeric animation speed");
        } else if ("advanced".equals(rootKey)) {
            addEntry("frozen", "Frozen apps & app state",
                    "IceBox-safe detection, disabled app launching and background state refresh");
        }
    }

    private void addEntry(String section, String title, String summary) {
        String key = "smart-section-" + section;
        if (findPreference(key) != null) return;

        Preference entry = new Preference(requireContext());
        entry.setKey(key);
        entry.setTitle(title);
        entry.setSummary(summary);
        entry.setFragment(SmartFeaturesSettingsFragment.class.getName());
        entry.getExtras().putString(SmartFeaturesSettingsFragment.ARG_SECTION, section);
        getPreferenceScreen().addPreference(entry);
    }
}
