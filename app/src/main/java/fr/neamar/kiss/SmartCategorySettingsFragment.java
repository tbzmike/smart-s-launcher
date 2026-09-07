package fr.neamar.kiss;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreference;

import fr.neamar.kiss.appusage.AppUsageTracker;
import fr.neamar.kiss.battery.BatteryMonitorStarter;
import fr.neamar.kiss.preference.ColorPreference;
import fr.neamar.kiss.preference.UiEditLock;
import fr.neamar.kiss.preference.UiLivePreviewPreference;
import fr.neamar.kiss.ui.SmartTextAppearance;
import fr.neamar.kiss.update.AppUpdater;

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
            addGlobalDefaultTextPreferences();
            addHighlightAppearancePreferences();
            addDefaultSearchAppearancePreferences();
            addVerticalHistoryAppearancePreferences();
            addEntry("wallpaper", "Smart wallpaper & blur",
                    "Smart Focus blur, icon tracking, blur strength and performance");
            addWorkspaceEntry();
        } else if ("ux-holder".equals(rootKey)) {
            addTileFlipSpeedPreference();
            addEntry("animations", "Smart animations & transitions",
                    "Scrolling, windows, popups, notifications, switching and numeric animation speed");
        } else if ("advanced".equals(rootKey)) {
            addBackgroundFeatureToggles();
            addUpdatePreferences();
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
        // The selector is XML-backed so it is always present, including after Settings recreation
        // and when this category is reached through Settings search. This fragment only attaches
        // Smart S live-refresh behavior; it no longer owns creation of the preference.
        ListPreference preference = findPreference("smart-history-layout");
        if (preference == null) return;
        preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            markLayoutDirty();
            return true;
        });
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

    /** True launcher-wide fallback typography. These controls live in Interface, not Smart Features. */
    private void addGlobalDefaultTextPreferences() {
        if (findPreference("smart-global-text-category") != null) return;

        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setKey("smart-global-text-category");
        category.setTitle("Default launcher text — global");
        getPreferenceScreen().addPreference(category);

        SwitchPreference inverter = new SwitchPreference(requireContext());
        inverter.setKey(SmartTextAppearance.PREF_TEXT_COLOR_INVERTER);
        inverter.setTitle("Text colour inverter");
        inverter.setSummary("Invert rendered text for wallpaper readability: dark colours become white and light colours become black. Stored colour choices are not changed.");
        inverter.setDefaultValue(false);
        inverter.setOnPreferenceChangeListener((preference, newValue) -> { markLayoutDirty(); return true; });
        category.addPreference(inverter);

        ListPreference family = new ListPreference(requireContext());
        family.setKey("smart-default-text-font-family");
        family.setTitle("Default font family");
        family.setSummary("Global fallback font, including the search bar and result text");
        family.setEntries(new CharSequence[]{"Sans", "Condensed", "Serif", "Monospace"});
        family.setEntryValues(new CharSequence[]{"sans", "condensed", "serif", "monospace"});
        family.setDefaultValue("sans");
        family.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        family.setOnPreferenceChangeListener((preference, newValue) -> { markLayoutDirty(); return true; });
        category.addPreference(family);

        ListPreference style = new ListPreference(requireContext());
        style.setKey("smart-default-text-font-style");
        style.setTitle("Default text style");
        style.setEntries(new CharSequence[]{"Normal", "Bold", "Italic", "Bold italic"});
        style.setEntryValues(new CharSequence[]{"normal", "bold", "italic", "bold_italic"});
        style.setDefaultValue("normal");
        style.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        style.setOnPreferenceChangeListener((preference, newValue) -> { markLayoutDirty(); return true; });
        category.addPreference(style);

        addSizeSlider(category, "smart-default-text-primary-size-sp", "Default primary text size",
                "Global title/input size, including text typed in the search bar", 10, 40, 18);
        addSizeSlider(category, "smart-default-text-secondary-size-sp", "Default secondary text size",
                "Global fallback size for subtitles, previews and secondary text", 8, 32, 14);
        addColorPreference(category, "smart-default-text-color", "Default text colour",
                "Global fallback colour, including search-bar text and result text");

        SwitchPreference shadow = new SwitchPreference(requireContext());
        shadow.setKey("smart-default-text-shadow");
        shadow.setTitle("Default text shadow");
        shadow.setSummary("Off removes the blurred/shadowed look from default launcher text, including the search bar");
        shadow.setDefaultValue(false);
        shadow.setOnPreferenceChangeListener((preference, newValue) -> { markLayoutDirty(); return true; });
        category.addPreference(shadow);
    }

    private void addHighlightAppearancePreferences() {
        if (findPreference("smart-highlight-appearance-category") != null) return;

        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setKey("smart-highlight-appearance-category");
        category.setTitle("Typed-search highlight text");
        getPreferenceScreen().addPreference(category);

        ListPreference style = new ListPreference(requireContext());
        style.setKey("smart-highlight-style");
        style.setTitle("Highlight text style");
        style.setSummary("Style applied only to the letters/words that match what you type");
        style.setEntries(new CharSequence[]{
                "Use existing highlight style", "Normal", "Bold", "Italic", "Bold italic", "Underline"
        });
        style.setEntryValues(new CharSequence[]{
                "legacy", "normal", "bold", "italic", "bold_italic", "underline"
        });
        style.setDefaultValue("legacy");
        style.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        style.setOnPreferenceChangeListener((preference, newValue) -> { markLayoutDirty(); return true; });
        category.addPreference(style);

        addSizeSlider(category, "smart-highlight-size-percent", "Highlight text size",
                "Resize only matching highlighted text. 100% keeps the normal result text size.",
                50, 200, 100);
        addColorPreference(category, "smart-highlight-color", "Highlight text colour",
                "Colour used for matching text when colour highlighting is enabled");
    }

    private void addBackgroundFeatureToggles() {
        if (findPreference("smart-background-feature-toggles") != null) return;

        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setKey("smart-background-feature-toggles");
        category.setTitle("Background features — on/off");
        getPreferenceScreen().addPreference(category);

        SwitchPreference appUsage = new SwitchPreference(requireContext());
        appUsage.setKey(AppUsageTracker.PREF_ENABLED);
        appUsage.setTitle("App usage tracking");
        appUsage.setSummary("Track the local 365-day app usage timeline. Off cancels its scheduled background job.");
        appUsage.setDefaultValue(true);
        appUsage.setOnPreferenceChangeListener((preference, newValue) -> {
            AppUsageTracker.setEnabled(requireContext(), Boolean.TRUE.equals(newValue));
            return true;
        });
        category.addPreference(appUsage);

        SwitchPreference notificationHistory = new SwitchPreference(requireContext());
        notificationHistory.setKey("enable-notification-history");
        notificationHistory.setTitle("Notification history");
        notificationHistory.setSummary("Allow new notifications to be added to launcher history. Live notification access remains separate.");
        notificationHistory.setDefaultValue(false);
        category.addPreference(notificationHistory);

        SwitchPreference batteryMonitor = new SwitchPreference(requireContext());
        batteryMonitor.setKey(BatteryMonitorStarter.PREF_ENABLED);
        batteryMonitor.setTitle("Battery monitor");
        batteryMonitor.setSummary("Run or stop Smart S battery sampling and its live background monitor.");
        batteryMonitor.setDefaultValue(true);
        batteryMonitor.setOnPreferenceChangeListener((preference, newValue) -> {
            BatteryMonitorStarter.setEnabled(requireContext(), Boolean.TRUE.equals(newValue));
            return true;
        });
        category.addPreference(batteryMonitor);
    }

    private void addUpdatePreferences() {
        if (findPreference("smart-update-category") != null) return;

        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setKey("smart-update-category");
        category.setTitle("App updates");
        getPreferenceScreen().addPreference(category);

        SwitchPreference automatic = new SwitchPreference(requireContext());
        automatic.setKey(AppUpdater.PREF_AUTO_UPDATE);
        automatic.setTitle("Automatic updates");
        automatic.setSummary("Automatically check GitHub Releases and download a newer compatible APK. Android will still ask you to approve installation.");
        automatic.setDefaultValue(false);
        category.addPreference(automatic);

        Preference manual = new Preference(requireContext());
        manual.setKey("smart-check-for-updates-now");
        manual.setTitle("Check for updates now");
        manual.setSummary("Manually check the latest Smart S Launcher GitHub release");
        manual.setOnPreferenceClickListener(preference -> {
            AppUpdater.checkForUpdates(requireContext(), true);
            return true;
        });
        category.addPreference(manual);
    }

    private void addDefaultSearchAppearancePreferences() {
        if (findPreference("smart-search-appearance-category") != null) return;

        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setKey("smart-search-appearance-category");
        category.setTitle("Search result text appearance");
        getPreferenceScreen().addPreference(category);

        addSizeSlider(category, "smart-search-title-size-sp", "Search title text size",
                "Size of app names, feature names, shortcut names, contacts and other result titles while searching",
                10, 40, 18);
        addFontPreference(category, "smart-search-title-font", "Search title font style", "sans_normal");
        addColorPreference(category, "smart-search-title-color", "Search title text color",
                "Default title color for search results");
        addSizeSlider(category, "smart-search-title-contrast", "Search title contrast",
                "100 keeps the selected color unchanged. Lower values soften it; higher values strengthen it.",
                25, 200, 100);

        addSizeSlider(category, "smart-search-body-size-sp", "Search body/subtitle size",
                "Size of tags, descriptions, numbers, dates and secondary search-result text",
                8, 32, 14);
        addFontPreference(category, "smart-search-body-font", "Search body font style", "sans_normal");
        addColorPreference(category, "smart-search-body-color", "Search body text color",
                "Default body/subtitle color for search results");
        addSizeSlider(category, "smart-search-body-contrast", "Search body contrast",
                "100 keeps the selected color unchanged. Lower values soften it; higher values strengthen it.",
                25, 200, 100);
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
        addColorPreference(category, "smart-list-label-color", "App label text color",
                "Choose the label color independently of message/body text");
        addSizeSlider(category, "smart-list-label-contrast", "App label contrast",
                "100 keeps the selected color unchanged. Lower values soften it; higher values strengthen it against the current theme.",
                25, 200, 100);

        addSizeSlider(category, "smart-list-body-size-sp", "Message/body text size",
                "Size of notification previews, tags, numbers, dates and message text",
                8, 32, 14);
        addFontPreference(category, "smart-list-body-font", "Message/body font style",
                "sans_normal");
        addColorPreference(category, "smart-list-body-color", "Message/body text color",
                "Choose body and notification-preview color independently of app labels");
        addSizeSlider(category, "smart-list-body-contrast", "Message/body contrast",
                "100 keeps the selected color unchanged. Lower values soften it; higher values strengthen it against the current theme.",
                25, 200, 100);

        addSizeSlider(category, "smart-history-meta-size-sp", "History timestamp text size",
                "Size of posted, opened, usage and count metadata shown on history items",
                8, 28, 12);
        addFontPreference(category, "smart-history-meta-font", "History timestamp font style",
                "sans_normal");
        addColorPreference(category, "smart-history-meta-color", "History timestamp text color",
                "Color used only for history timestamps, usage duration and open counts");
        addSizeSlider(category, "smart-history-meta-contrast", "History timestamp contrast",
                "Adjust timestamp/metadata contrast independently of labels and body text",
                25, 200, 100);

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

    private void addColorPreference(PreferenceCategory category, String key, String title,
                                    String summary) {
        ColorPreference preference = new ColorPreference(requireContext());
        preference.setKey(key);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setDefaultValue(UIColors.colorToString(UIColors.COLOR_SYSTEM));
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            markLayoutDirty();
            return true;
        });
        category.addPreference(preference);
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
