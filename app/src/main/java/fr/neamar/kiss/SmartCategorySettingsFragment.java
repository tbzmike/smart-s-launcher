package fr.neamar.kiss;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

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
            addHistoryLayoutPreference();
            addEntry("notifications", "Smart notifications & history",
                    "Timeline, notification actions, persistent history and notification search");
        } else if ("ui-holder".equals(rootKey)) {
            addEntry("wallpaper", "Smart wallpaper & blur",
                    "Smart Focus blur, icon tracking, blur strength and performance");
            addWorkspaceEntry();
        } else if ("ux-holder".equals(rootKey)) {
            addEntry("animations", "Smart animations & transitions",
                    "Scrolling, windows, popups, notifications, switching and numeric animation speed");
        } else if ("advanced".equals(rootKey)) {
            addEntry("frozen", "Frozen apps & app state",
                    "IceBox-safe detection, disabled app launching and background state refresh");
        }
    }

    private void addHistoryLayoutPreference() {
        String key = "smart-history-layout";
        if (findPreference(key) != null) return;

        ListPreference preference = new ListPreference(requireContext());
        preference.setKey(key);
        preference.setTitle("App history layout");
        preference.setEntries(new CharSequence[]{
                "Vertical list",
                "Horizontal icons",
                "Horizontal cards",
                "Horizontal app names"
        });
        preference.setEntryValues(new CharSequence[]{
                "vertical",
                "horizontal_icons",
                "horizontal_cards",
                "horizontal_names"
        });
        preference.setDefaultValue("vertical");
        preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                    .putBoolean("require-layout-update", true)
                    .apply();
            return true;
        });
        getPreferenceScreen().addPreference(preference);
    }

    private void addWorkspaceEntry() {
        String key = "smart-section-workspace";
        if (findPreference(key) != null) return;

        Preference entry = new Preference(requireContext());
        entry.setKey(key);
        entry.setTitle("Flexible workspace");
        entry.setSummary("Split the launcher into resizable panes for apps, history, widgets and future Smart S panels");
        entry.setFragment(WorkspaceSettingsFragment.class.getName());
        getPreferenceScreen().addPreference(entry);
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
