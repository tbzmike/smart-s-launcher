package fr.neamar.kiss;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;

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
            addHistorySizingPreferences();
            addEntry("notifications", "Smart notifications & history",
                    "Timeline, notification actions, persistent history and notification search");
        } else if ("ui-holder".equals(rootKey)) {
            addVerticalHistoryAppearancePreferences();
            addEntry("wallpaper", "Smart wallpaper & blur",
                    "Smart Focus blur, icon tracking, blur strength and performance");
            addWorkspaceEntry();
        } else if ("ux-holder".equals(rootKey)) {
            addTileFlipSpeedPreference();
            addEntry("animations", "Smart animations & transitions",
                    "Scrolling, windows, popups, notifications, switching and numeric animation speed");
        } else if ("advanced".equals(rootKey)) {
            addEntry("frozen", "Frozen apps & app state",
                    "IceBox-safe detection, disabled app launching and background state refresh");
        }
    }

    private void addTileFlipSpeedPreference() {
        String key = "smart-launch-flip-speed";
        if (findPreference(key) != null) return;

        ListPreference preference = new ListPreference(requireContext());
        preference.setKey(key);
        preference.setTitle("Tile flip speed");
        preference.setSummary("Speed of the live tile flip before an app or shortcut opens");
        preference.setEntries(new CharSequence[]{
                "Very slow", "Slow", "Slightly slow", "Normal", "Fast", "Very fast"
        });
        preference.setEntryValues(new CharSequence[]{
                "0.55", "0.70", "0.85", "1.00", "1.20", "1.45"
        });
        preference.setDefaultValue("0.85");
        preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        getPreferenceScreen().addPreference(preference);
    }

    private void addHistoryLayoutPreference() {
        String key = "smart-history-layout";
        if (findPreference(key) != null) return;

        ListPreference preference = new ListPreference(requireContext());
        preference.setKey(key);
        preference.setTitle("App history layout");
        preference.setEntries(new CharSequence[]{
                "Vertical list", "Vertical cards", "Horizontal icons", "Horizontal cards",
                "Horizontal app names", "Square-U cards"
        });
        preference.setEntryValues(new CharSequence[]{
                "vertical", "vertical_cards", "horizontal_icons", "horizontal_cards",
                "horizontal_names", "square_u"
        });
        preference.setDefaultValue("vertical");
        preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
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

        addSizeSlider(category, "smart-list-card-height-percent", "Vertical card height",
                "Resize the Vertical Cards view", 70, 170, 100);
        addSizeSlider(category, "smart-list-card-icon-percent", "Vertical card icon/profile size",
                "Resize the foreground app icon or available profile artwork", 60, 180, 100);
        addSizeSlider(category, "smart-list-card-name-percent", "Vertical card name size",
                "Resize the full auto-scrolling name underneath each card", 70, 170, 100);
        addSizeSlider(category, "smart-list-card-radius-dp", "Vertical card corner radius",
                "Adjust rounded card geometry", 6, 40, 22);
        addSizeSlider(category, "smart-list-card-elevation-dp", "Vertical card depth",
                "Adjust 3D elevation/shadow depth", 0, 24, 9);
        addSizeSlider(category, "smart-list-card-spacing-dp", "Vertical card spacing",
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
                "Resize vertical history rows independently", 70, 220, 100);
        addSizeSlider(category, "smart-list-icon-size-percent", "Vertical list icon size",
                "Resize only icons in the vertical history list", 50, 240, 100);
    }

    /** Controls requested specifically for the classic Vertical List history renderer. */
    private void addVerticalHistoryAppearancePreferences() {
        if (findPreference("smart-list-appearance-category") != null) return;

        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setKey("smart-list-appearance-category");
        category.setTitle("Vertical history appearance");
        getPreferenceScreen().addPreference(category);

        addSizeSlider(category, "smart-list-label-size-sp", "App label text size",
                "Size of app, shortcut, contact and communication titles in Vertical List history",
                10, 40, 18);
        addFontPreference(category, "smart-list-label-font", "App label font style",
                "sans_bold");

        addSizeSlider(category, "smart-list-body-size-sp", "Message/body text size",
                "Size of notification previews, tags, numbers, dates and message text",
                8, 32, 14);
        addFontPreference(category, "smart-list-body-font", "Message/body font style",
                "sans_normal");

        addSizeSlider(category, "smart-list-icon-size-percent", "App icon size",
                "Resize icons in Vertical List history without changing the text",
                50, 240, 110);
        addSizeSlider(category, "smart-list-row-spacing-dp", "Space between history items",
                "Add real breathing room between rows. At 24dp body text can use 2 lines; at 56dp or more it can use 3 lines.",
                0, 96, 4);

        Preference iconInfo = new Preference(requireContext());
        iconInfo.setKey("smart-list-icon-selection-info");
        iconInfo.setTitle("App icon selection");
        iconInfo.setSummary("Collective icons use Interface → Icons → Icon pack. Individual app icons can be changed from the app's long-press Custom icon action when the selected icon pack provides alternatives.");
        iconInfo.setSelectable(false);
        category.addPreference(iconInfo);
    }

    private void addFontPreference(PreferenceCategory category, String key, String title,
                                   String defaultValue) {
        ListPreference preference = new ListPreference(requireContext());
        preference.setKey(key);
        preference.setTitle(title);
        preference.setEntries(new CharSequence[]{
                "Sans · Normal", "Sans · Bold", "Sans · Italic", "Sans · Bold italic",
                "Condensed · Normal", "Condensed · Bold", "Serif · Normal", "Serif · Bold",
                "Monospace · Normal", "Monospace · Bold"
        });
        preference.setEntryValues(new CharSequence[]{
                "sans_normal", "sans_bold", "sans_italic", "sans_bold_italic",
                "condensed_normal", "condensed_bold", "serif_normal", "serif_bold",
                "monospace_normal", "monospace_bold"
        });
        preference.setDefaultValue(defaultValue);
        preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            markLayoutDirty();
            return true;
        });
        category.addPreference(preference);
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
