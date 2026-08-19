package fr.neamar.kiss;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreference;

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
        PreferenceGroup root = getPreferenceScreen();
        if (root == null) return;

        if ("workspace".equals(section)) {
            clearPreferenceGroup(root);
            addWorkspacePreferences(root);
            root.setTitle("Flexible workspace");
            return;
        }

        if (section != null && !section.isEmpty()) {
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

        replaceAnimationSpeedControl(root);
    }

    private void replaceAnimationSpeedControl(PreferenceGroup root) {
        for (int i = 0; i < root.getPreferenceCount(); i++) {
            Preference child = root.getPreference(i);
            if (!(child instanceof PreferenceGroup)) continue;
            PreferenceGroup group = (PreferenceGroup) child;
            Preference oldSpeed = group.findPreference("smart-animation-speed");
            if (oldSpeed == null) continue;

            int percent = readAnimationSpeedPercentSafely();
            Object storedPercent = prefs.getAll().get("smart-animation-speed-percent");
            if (!(storedPercent instanceof Integer) || ((Integer) storedPercent) != percent) {
                // SeekBarPreference persists an Integer. Remove any legacy String/Float value first
                // so Preference cannot throw ClassCastException while binding this screen.
                prefs.edit()
                        .remove("smart-animation-speed-percent")
                        .putInt("smart-animation-speed-percent", percent)
                        .apply();
            }

            group.removePreference(oldSpeed);
            SeekBarPreference speed = new SeekBarPreference(requireContext());
            speed.setKey("smart-animation-speed-percent");
            speed.setTitle("Animation speed (%)");
            speed.setSummary("5% is extremely slow · 100% is normal · 300% is very fast");
            speed.setMin(5);
            speed.setMax(300);
            speed.setSeekBarIncrement(5);
            speed.setShowSeekBarValue(true);
            speed.setDefaultValue(100);
            speed.setDependency("smart-animations-enabled");
            group.addPreference(speed);
            return;
        }
    }

    private int readAnimationSpeedPercentSafely() {
        Object rawPercent = prefs.getAll().get("smart-animation-speed-percent");
        Integer parsedPercent = parsePercent(rawPercent);
        if (parsedPercent != null) return clampPercent(parsedPercent);

        Object legacySpeed = prefs.getAll().get("smart-animation-speed");
        if (legacySpeed instanceof Number) {
            return clampPercent(Math.round(((Number) legacySpeed).floatValue() * 100f));
        }
        if (legacySpeed instanceof String) {
            try {
                return clampPercent(Math.round(Float.parseFloat((String) legacySpeed) * 100f));
            } catch (NumberFormatException ignored) {
                // Fall through to the safe default.
            }
        }
        return 100;
    }

    @Nullable
    private Integer parsePercent(@Nullable Object value) {
        if (value instanceof Number) return Math.round(((Number) value).floatValue());
        if (value instanceof String) {
            try {
                return Math.round(Float.parseFloat((String) value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int clampPercent(int percent) {
        return Math.max(5, Math.min(300, percent));
    }

    private void addWorkspacePreferences(PreferenceGroup root) {
        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setTitle("Flexible panes");
        root.addPreference(category);

        SwitchPreference enabled = new SwitchPreference(requireContext());
        enabled.setKey("smart-workspace-enabled");
        enabled.setTitle("Enable flexible workspace");
        enabled.setSummary("Use resizable launcher panes instead of one fixed full-screen content area");
        enabled.setDefaultValue(false);
        category.addPreference(enabled);

        ListPreference orientation = new ListPreference(requireContext());
        orientation.setKey("smart-workspace-orientation");
        orientation.setTitle("Split direction");
        orientation.setEntries(new CharSequence[]{"Left / right", "Top / bottom"});
        orientation.setEntryValues(new CharSequence[]{"horizontal", "vertical"});
        orientation.setDefaultValue("horizontal");
        orientation.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        orientation.setDependency("smart-workspace-enabled");
        category.addPreference(orientation);

        ListPreference primaryContent = new ListPreference(requireContext());
        primaryContent.setKey("smart-workspace-primary-content");
        primaryContent.setTitle("First pane content");
        primaryContent.setEntries(new CharSequence[]{"Apps & history", "Widgets"});
        primaryContent.setEntryValues(new CharSequence[]{"history", "widgets"});
        primaryContent.setDefaultValue("history");
        primaryContent.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        primaryContent.setDependency("smart-workspace-enabled");
        category.addPreference(primaryContent);

        SeekBarPreference split = new SeekBarPreference(requireContext());
        split.setKey("smart-workspace-split-percent");
        split.setTitle("First pane size (%)");
        split.setSummary("Initial size of the first pane; the divider can also be dragged directly on the launcher");
        split.setMin(15);
        split.setMax(85);
        split.setSeekBarIncrement(1);
        split.setShowSeekBarValue(true);
        split.setDefaultValue(50);
        split.setDependency("smart-workspace-enabled");
        category.addPreference(split);

        SwitchPreference draggable = new SwitchPreference(requireContext());
        draggable.setKey("smart-workspace-draggable");
        draggable.setTitle("Resizable divider");
        draggable.setSummary("Drag the divider continuously between 15% and 85% of the available launcher area");
        draggable.setDefaultValue(true);
        draggable.setDependency("smart-workspace-enabled");
        category.addPreference(draggable);

        Preference note = new Preference(requireContext());
        note.setTitle("Pane architecture");
        note.setSummary("The first version supports Apps & history and Android widgets on either side. The pane system is designed for additional Smart S panels and Smart S widgets later.");
        note.setSelectable(false);
        category.addPreference(note);
    }

    private void clearPreferenceGroup(PreferenceGroup group) {
        for (int i = group.getPreferenceCount() - 1; i >= 0; i--) {
            group.removePreference(group.getPreference(i));
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
            startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }

        if (key != null && key.startsWith("smart-workspace-")) {
            // The workspace reparents core launcher views, so apply structural changes on the next
            // MainActivity resume rather than mutating the home layout while Settings is on top.
            sharedPreferences.edit().putBoolean("require-layout-update", true).apply();
        }
    }
}
