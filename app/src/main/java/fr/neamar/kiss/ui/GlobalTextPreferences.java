package fr.neamar.kiss.ui;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

import fr.neamar.kiss.UIColors;
import fr.neamar.kiss.preference.ColorPreference;

/** Inserts the true launcher-wide text overrides at the top of Interface text configuration. */
final class GlobalTextPreferences {
    private GlobalTextPreferences() { }

    static void install(PreferenceFragmentCompat fragment) {
        PreferenceCategory category = fragment.findPreference("smart-global-text-category");
        if (category == null) return;

        if (fragment.findPreference(GlobalTextStyler.PREF_GLOBAL_TEXT_COLOR) == null) {
            ColorPreference color = new ColorPreference(fragment.requireContext());
            color.setKey(GlobalTextStyler.PREF_GLOBAL_TEXT_COLOR);
            color.setTitle("Global text colour override");
            color.setSummary("Changes text colour across the whole Smart S Launcher. System keeps each view's normal colour.");
            color.setDefaultValue(UIColors.colorToString(UIColors.COLOR_SYSTEM));
            color.setOrder(-2000);
            category.addPreference(color);
        }

        if (fragment.findPreference(GlobalTextStyler.PREF_GLOBAL_TEXT_WEIGHT) == null) {
            SeekBarPreference weight = new SeekBarPreference(fragment.requireContext());
            weight.setKey(GlobalTextStyler.PREF_GLOBAL_TEXT_WEIGHT);
            weight.setTitle("Global text boldness");
            weight.setSummary("100 = thin, 400 = normal, 700 = bold, 900 = black. Applies launcher-wide while preserving italic text.");
            weight.setMin(100);
            weight.setMax(900);
            weight.setSeekBarIncrement(50);
            weight.setShowSeekBarValue(true);
            weight.setUpdatesContinuously(true);
            weight.setDefaultValue(GlobalTextStyler.DEFAULT_WEIGHT);
            weight.setOrder(-1999);
            category.addPreference(weight);
        }
    }
}
