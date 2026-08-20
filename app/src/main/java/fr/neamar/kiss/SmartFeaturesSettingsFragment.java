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

import fr.neamar.kiss.preference.UiLivePreviewPreference;

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

        Bundle args = getArguments();
        String section = args == null ? null : args.getString(ARG_SECTION);
        if ("animations".equals(section)) {
            PreferenceGroup root = getPreferenceManager().createPreferenceScreen(requireContext());
            setPreferenceScreen((androidx.preference.PreferenceScreen) root);
            root.setTitle("Smart animations & transitions");
            addLivePreview(root, UiLivePreviewPreference.TYPE_ANIMATIONS);
            addAnimationPreferences(root);
            return;
        }

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
            addLivePreview(root, UiLivePreviewPreference.TYPE_WORKSPACE);
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
                case "wallpaper":
                    root.setTitle("Smart wallpaper & blur");
                    addLivePreview(root, UiLivePreviewPreference.TYPE_WALLPAPER);
                    break;
                default:
                    break;
            }
        }

        replaceAnimationSpeedControl(root);
    }

    private void addLivePreview(PreferenceGroup root, String type) {
        if (root.findPreference("live-preview-" + type) != null) return;
        root.addPreference(new UiLivePreviewPreference(requireContext(), type));
    }

    private void addAnimationPreferences(PreferenceGroup root) {
        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setTitle(R.string.smart_animations_title);
        root.addPreference(category);

        SwitchPreference enabled = new SwitchPreference(requireContext());
        enabled.setKey("smart-animations-enabled");
        enabled.setTitle(R.string.smart_animation_master);
        enabled.setSummary(R.string.smart_animation_master_summary);
        enabled.setDefaultValue(true);
        category.addPreference(enabled);

        addAnimationList(category, "smart-animation-scroll", R.string.smart_animation_scroll,
                R.array.smart_scroll_animation_entries, R.array.smart_scroll_animation_values, "classic");
        addAnimationList(category, "smart-animation-window-enter", R.string.smart_animation_window_enter,
                R.array.smart_enter_animation_entries, R.array.smart_enter_animation_values, "fade");
        addAnimationList(category, "smart-animation-window-exit", R.string.smart_animation_window_exit,
                R.array.smart_exit_animation_entries, R.array.smart_exit_animation_values, "fade");
        addAnimationList(category, "smart-animation-popup-open", R.string.smart_animation_popup_open,
                R.array.smart_popup_animation_entries, R.array.smart_popup_animation_values, "scale");
        addAnimationList(category, "smart-animation-popup-close", R.string.smart_animation_popup_close,
                R.array.smart_exit_animation_entries, R.array.smart_exit_animation_values, "shrink");
        addAnimationList(category, "smart-animation-notification-expand", R.string.smart_animation_notification_expand,
                R.array.smart_popup_animation_entries, R.array.smart_popup_animation_values, "spring");
        addAnimationList(category, "smart-animation-view-switch", R.string.smart_animation_view_switch,
                R.array.smart_switch_animation_entries, R.array.smart_switch_animation_values, "crossfade");
        addAnimationList(category, "smart-animation-toast", R.string.smart_animation_toast,
                R.array.smart_toast_animation_entries, R.array.smart_toast_animation_values, "fade");

        int percent = readAnimationSpeedPercentSafely();
        Object storedPercent = prefs.getAll().get("smart-animation-speed-percent");
        if (!(storedPercent instanceof Integer) || ((Integer) storedPercent) != percent) {
            prefs.edit().remove("smart-animation-speed-percent")
                    .putInt("smart-animation-speed-percent", percent).apply();
        }

        SeekBarPreference speed = new SeekBarPreference(requireContext());
        speed.setKey("smart-animation-speed-percent");
        speed.setTitle("Animation speed (%)");
        speed.setSummary("5% is extremely slow · 100% is normal · 300% is very fast");
        speed.setMin(5);
        speed.setMax(300);
        speed.setSeekBarIncrement(5);
        speed.setShowSeekBarValue(true);
        speed.setUpdatesContinuously(true);
        speed.setDefaultValue(100);
        category.addPreference(speed);

        updateAnimationControlsEnabled(enabled.isChecked());
    }

    private void addAnimationList(PreferenceGroup category, String key, int titleRes,
                                  int entriesRes, int valuesRes, String defaultValue) {
        ListPreference preference = new ListPreference(requireContext());
        preference.setKey(key);
        preference.setTitle(titleRes);
        preference.setEntries(entriesRes);
        preference.setEntryValues(valuesRes);
        preference.setDefaultValue(defaultValue);
        preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        category.addPreference(preference);
    }

    private void updateAnimationControlsEnabled(boolean enabled) {
        PreferenceGroup root = getPreferenceScreen();
        if (root == null) return;
        setAnimationChildEnabled(root, "smart-animation-scroll", enabled);
        setAnimationChildEnabled(root, "smart-animation-window-enter", enabled);
        setAnimationChildEnabled(root, "smart-animation-window-exit", enabled);
        setAnimationChildEnabled(root, "smart-animation-popup-open", enabled);
        setAnimationChildEnabled(root, "smart-animation-popup-close", enabled);
        setAnimationChildEnabled(root, "smart-animation-notification-expand", enabled);
        setAnimationChildEnabled(root, "smart-animation-view-switch", enabled);
        setAnimationChildEnabled(root, "smart-animation-toast", enabled);
        setAnimationChildEnabled(root, "smart-animation-speed-percent", enabled);
    }

    private void setAnimationChildEnabled(PreferenceGroup root, String key, boolean enabled) {
        Preference preference = root.findPreference(key);
        if (preference != null) preference.setEnabled(enabled);
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
                prefs.edit().remove("smart-animation-speed-percent")
                        .putInt("smart-animation-speed-percent", percent).apply();
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
            speed.setUpdatesContinuously(true);
            speed.setDefaultValue(100);
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
        split.setUpdatesContinuously(true);
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

        if ("smart-animations-enabled".equals(key)) {
            updateAnimationControlsEnabled(sharedPreferences.getBoolean(key, true));
        }

        if (key != null && key.startsWith("smart-workspace-")) {
            sharedPreferences.edit().putBoolean("require-layout-update", true).apply();
        }
    }
}
