package fr.neamar.kiss;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

/**
 * Adds a single Smart S configuration entry to the relevant existing KISS settings category.
 * The legacy SettingsFragment remains responsible for loading and managing the category itself.
 */
public class SmartCategorySettingsFragment extends SettingsFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        addSmartSectionEntry(rootKey);
    }

    private void addSmartSectionEntry(@Nullable String rootKey) {
        String section;
        String title;
        String summary;

        if ("history_category".equals(rootKey)) {
            section = "notifications";
            title = "Smart notifications & history";
            summary = "Timeline, notification actions, persistent history and notification search";
        } else if ("ui-holder".equals(rootKey)) {
            section = "wallpaper";
            title = "Smart wallpaper & blur";
            summary = "Smart Focus blur, icon tracking, blur strength and performance";
        } else if ("ux-holder".equals(rootKey)) {
            section = "animations";
            title = "Smart animations & transitions";
            summary = "Scrolling, windows, popups, notifications, switching and animation speed";
        } else if ("advanced".equals(rootKey)) {
            section = "frozen";
            title = "Frozen apps & app state";
            summary = "IceBox-safe detection, disabled app launching and background state refresh";
        } else {
            return;
        }

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
