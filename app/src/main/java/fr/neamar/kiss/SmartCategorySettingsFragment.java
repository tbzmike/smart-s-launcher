package fr.neamar.kiss;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreference;

import fr.neamar.kiss.preference.UiEditLock;
import fr.neamar.kiss.preference.UiLivePreviewPreference;

/**
 * Adds Smart S configuration entries to the relevant existing KISS settings category.
 * The legacy SettingsFragment remains responsible for loading and managing the category itself.
 */
public class SmartCategorySettingsFragment extends SettingsFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        addLivePreview(rootKey);
        addSmartSectionEntries(rootKey);
        if (UiEditLock.isLockableRoot(rootKey)) {
            UiEditLock.install(requireContext(), getPreferenceScreen());
        }
    }

    private void addLivePreview(@Nullable String rootKey) {
        String type = null;
        if ("history_category".equals(rootKey)) {
            type = UiLivePreviewPreference.TYPE_HISTORY;
        } else if ("ui-holder".equals(rootKey) || "theme-customisation".equals(rootKey)) {
            type = UiLivePreviewPreference.TYPE_UI;
        } else if ("ux-holder".equals(rootKey)) {
            type = UiLivePreviewPreference.TYPE_UX;
        }
        if (type == null || findPreference("live-preview-" + type) != null) return;
        getPreferenceScreen().addPreference(new UiLivePreviewPreference(requireContext(), type));
    }

    private void addSmartSectionEntries(@Nullable String rootKey) {
        if ("history_category".equals(rootKey)) {
            addHistoryLayoutPreference();
            addSmartCardListPreference();
            addHistorySizingPreferences();
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
                "Horizontal app names",
                "Square-U cards"
        });
        preference.setEntryValues(new CharSequence[]{
                "vertical",
                "horizontal_icons",
                "horizontal_cards",
                "horizontal_names",
                "square_u"
        });
        preference.setDefaultValue("vertical");
        preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            markLayoutDirty();
            return true;
        });
        getPreferenceScreen().addPreference(preference);
    }

    private void addSmartCardListPreference() {
        String key = "smart-card-list-enabled";
        if (findPreference(key) != null) return;

        SwitchPreference preference = new SwitchPreference(requireContext());
        preference.setKey(key);
        preference.setTitle("Smart vertical card list");
        preference.setSummary("Use rich 3D cards with the full name underneath while preserving list-view actions, notifications, usage details, contacts, shortcuts and frozen-app behaviour");
        preference.setDefaultValue(false);
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            if (Boolean.TRUE.equals(newValue)) {
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                        .putString("smart-history-layout", "vertical")
                        .apply();
            }
            markLayoutDirty();
            return true;
        });
        getPreferenceScreen().addPreference(preference);
    }

    private void addHistorySizingPreferences() {
        if (findPreference("smart-history-sizing-category") != null) return;

        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setKey("smart-history-sizing-category");
        category.setTitle("History layout sizing");
        getPreferenceScreen().addPreference(category);

        addSizeSlider(category, "smart-list-card-height-percent", "Smart card height",
                "Resize the vertical Smart Card List cards", 70, 170, 100);
        addSizeSlider(category, "smart-list-card-icon-percent", "Smart card icon/profile size",
                "Resize the foreground app icon or available profile artwork", 60, 180, 100);
        addSizeSlider(category, "smart-list-card-name-percent", "Smart card name size",
                "Resize the full auto-scrolling name underneath each card", 70, 170, 100);
        addSizeSlider(category, "smart-list-card-radius-dp", "Smart card corner radius",
                "Adjust rounded card geometry", 6, 40, 22);
        addSizeSlider(category, "smart-list-card-elevation-dp", "Smart card depth",
                "Adjust 3D elevation/shadow depth", 0, 24, 9);
        addSizeSlider(category, "smart-list-card-spacing-dp", "Smart card spacing",
                "Adjust vertical space between cards", 4, 36, 12);

        addSizeSlider(category, "smart-u-tile-size-percent", "Square-U tile size",
                "Resize Square-U cards without changing their icons", 70, 150, 100);
        addSizeSlider(category, "smart-u-icon-size-percent", "Square-U icon size",
                "Resize only icons inside Square-U cards", 60, 160, 100);
        addSizeSlider(category, "smart-u-notification-panel-size-percent", "Square-U notification box size",
                "Resize only the middle notification box", 55, 150, 100);
        addSizeSlider(category, "smart-u-notification-content-size-percent", "Square-U notification content size",
                "Resize notification rows and content independently of the box", 65, 140, 100);
        addSizeSlider(category, "smart-u-notification-gap-dp", "Square-U notification gap",
                "Space between the middle notification box and surrounding cards", 8, 96, 28);

        addSizeSlider(category, "smart-horizontal-tile-size-percent", "Horizontal tile/card size",
                "Resize horizontal tiles and cards independently", 65, 160, 100);
        addSizeSlider(category, "smart-horizontal-icon-size-percent", "Horizontal icon size",
                "Resize only icons in horizontal history views", 60, 170, 100);

        addSizeSlider(category, "smart-list-row-size-percent", "Vertical list row size",
                "Resize vertical history rows independently", 70, 160, 100);
        addSizeSlider(category, "smart-list-icon-size-percent", "Vertical list icon size",
                "Resize only icons in the vertical history list", 60, 170, 100);
    }

    private void addSizeSlider(PreferenceCategory category, String key, String title,
                               String summary, int min, int max, int defaultValue) {
        SeekBarPreference slider = new SeekBarPreference(requireContext());
        slider.setKey(key);
        slider.setTitle(title);
        slider.setSummary(summary);
        slider.setMin(min);
        slider.setMax(max);
        slider.setSeekBarIncrement(1);
        slider.setShowSeekBarValue(true);
        slider.setUpdatesContinuously(true);
        slider.setDefaultValue(defaultValue);
        slider.setOnPreferenceChangeListener((preference, newValue) -> {
            markLayoutDirty();
            return true;
        });
        category.addPreference(slider);
    }

    private void markLayoutDirty() {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                .putBoolean("require-layout-update", true)
                .apply();
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
