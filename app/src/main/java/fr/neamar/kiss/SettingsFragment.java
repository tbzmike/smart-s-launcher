package fr.neamar.kiss;

import android.app.role.RoleManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreference;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.broadcast.IncomingCallHandler;
import fr.neamar.kiss.dataprovider.simpleprovider.SearchProvider;
import fr.neamar.kiss.dataprovider.simpleprovider.TagsProvider;
import fr.neamar.kiss.forwarder.InterfaceTweaks;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.TagDummyPojo;
import fr.neamar.kiss.preference.AddSearchProviderPreference;
import fr.neamar.kiss.preference.AddSearchProviderPreferenceDialogFragment;
import fr.neamar.kiss.preference.ColorPreference;
import fr.neamar.kiss.preference.ColorPreferenceDialogFragment;
import fr.neamar.kiss.preference.DefaultLauncherPreference;
import fr.neamar.kiss.preference.DefaultSearchProviderSelectPreference;
import fr.neamar.kiss.preference.DialogShowingPreference;
import fr.neamar.kiss.preference.DialogShowingPreferenceDialogFragment;
import fr.neamar.kiss.preference.ExportSettingsPreference;
import fr.neamar.kiss.preference.ImportSettingsPreference;
import fr.neamar.kiss.preference.LaunchPojoSelectPreference;
import fr.neamar.kiss.preference.SelectCustomSearchProvidersPreference;
import fr.neamar.kiss.searcher.AppSourceMetadataUpdater;
import fr.neamar.kiss.searcher.QuerySearcher;
import fr.neamar.kiss.searcher.SemanticEmbeddingScorer;
import fr.neamar.kiss.searcher.SemanticHnswIndex;
import fr.neamar.kiss.ui.BuiltInKeyboardSizing;
import fr.neamar.kiss.ui.SearchEditText;
import fr.neamar.kiss.update.AppUpdater;
import fr.neamar.kiss.utils.DrawableUtils;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.Permission;
import fr.neamar.kiss.utils.ShortcutUtil;

public class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener, PreferenceFragmentCompat.OnPreferenceDisplayDialogCallback {
    private static final String TAG = SettingsFragment.class.getSimpleName();
    private static final int REQUEST_CALL_SCREENING_APP = 1;
    private static final String DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragment.DIALOG";
    private static final String PREF_CHOOSE_SYSTEM_KEYBOARD = "choose-system-keyboard";

    private static final List<String> PREF_LISTS_WITH_DEPENDENCY = Arrays.asList(
            "gesture-up", "gesture-down",
            "gesture-left", "gesture-right",
            "gesture-long-press"
    );

    private SharedPreferences prefs;

    private Permission permissionManager;

    public SettingsFragment() {
        super();
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        setPreferencesFromResource(R.xml.preferences, rootKey);
        addPixelLauncherCleanupPreference(rootKey);
        addAppUpdatePreferences(rootKey);
        addSearchKeyboardPreferences();
        try {
            addSemanticSearchPreferences(rootKey);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to create semantic search settings; keeping core settings available", e);
        }

        if (prefs.getStringSet("selected-search-provider-names", null) == null) {
            // If null, it means this setting has never been accessed before
            // In this case, null != [] ([] happens when the user manually unselected every single option)
            // So, when null, we know it's the first time opening this setting and we can write the default value.
            // note: other preferences are initialized automatically in MainActivity.onCreate() from the preferences XML,
            // but this preference isn't defined in the XML so can't be initialized that easily.
            prefs.edit().putStringSet("selected-search-provider-names", SearchProvider.getSelectedSearchProviders(prefs)).apply();
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            removePreference("gestures-holder", "double-tap");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            removePreference("colors-section", "black-notification-icons");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            removePreference("advanced", "enable-notifications");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            removePreference("icons-section", DrawableUtils.KEY_THEMED_ICONS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            removePreference("colors-section", "notification-bar-color");
        }
        if (!ShortcutUtil.canDeviceShowShortcuts()) {
            removePreference("exclude_apps_category", "edit-excluded-app-shortcuts");
            removePreference("exclude_apps_category", "reset-excluded-app-shortcuts");
            removePreference("search-providers", "enable-shortcuts");
            removePreference("search-providers", "reset-shortcuts");
        }

        try {
            updateItemsToRun();
            fixSummaries();
            updateNightMode();
        } catch (RuntimeException e) {
            Log.e(TAG, "Non-critical Settings post-processing failed; keeping SettingsActivity open", e);
        }

        permissionManager = new Permission(getActivity());
    }

    private void addPixelLauncherCleanupPreference(@Nullable String rootKey) {
        PreferenceGroup parent;
        if ("ui-holder".equals(rootKey)) {
            parent = getPreferenceScreen();
        } else {
            parent = findPreference("ui-holder");
        }
        if (parent == null || parent.findPreference("performance-background-category") != null) return;

        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setKey("performance-background-category");
        category.setTitle("Performance & background");
        parent.addPreference(category);

        SwitchPreference killPixelLauncher = new SwitchPreference(requireContext());
        killPixelLauncher.setKey(PixelLauncherScreenOffReceiver.PREFERENCE_KEY);
        killPixelLauncher.setTitle("Force-stop Pixel Launcher when screen turns off");
        killPixelLauncher.setSummary("Uses Smart S root access to stop com.google.android.apps.nexuslauncher on every screen-off event and release its background memory. No background service is kept running for this feature.");
        killPixelLauncher.setDefaultValue(false);
        category.addPreference(killPixelLauncher);
    }

    private void addAppUpdatePreferences(@Nullable String rootKey) {
        PreferenceGroup parent;
        if ("advanced".equals(rootKey)) {
            parent = getPreferenceScreen();
        } else {
            parent = findPreference("advanced");
        }
        if (parent == null || parent.findPreference("smart-app-update-category") != null) return;

        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setKey("smart-app-update-category");
        category.setTitle("App updates");
        parent.addPreference(category);

        Preference check = new Preference(requireContext());
        check.setKey("smart-check-for-update");
        check.setTitle("Check for Smart S Launcher update");
        check.setSummary(AppUpdater.currentStatus(requireContext()));
        check.setOnPreferenceClickListener(preference -> {
            preference.setSummary("Checking the latest verified GitHub release…");
            AppUpdater.checkForUpdates(requireActivity(), true);
            return true;
        });
        category.addPreference(check);

        SwitchPreference automatic = new SwitchPreference(requireContext());
        automatic.setKey(AppUpdater.PREF_AUTO_UPDATE);
        automatic.setTitle("Automatic update checks");
        automatic.setSummary("Check GitHub once per day and download only a newer verified, signed production release.");
        automatic.setDefaultValue(false);
        category.addPreference(automatic);

        Preference install = new Preference(requireContext());
        install.setKey("smart-install-downloaded-update");
        install.setTitle("Install downloaded update");
        install.setSummary("Re-verifies the APK package, signing certificate and version before opening Android's installer.");
        install.setOnPreferenceClickListener(preference -> {
            AppUpdater.installReadyUpdate(requireContext());
            return true;
        });
        category.addPreference(install);

        Preference cancel = new Preference(requireContext());
        cancel.setKey("smart-cancel-update-download");
        cancel.setTitle("Cancel update download");
        cancel.setSummary("Stops the updater and removes its partial APK.");
        cancel.setOnPreferenceClickListener(preference -> {
            AppUpdater.cancelDownload(requireContext());
            return true;
        });
        category.addPreference(cancel);
    }

    private void addSearchKeyboardPreferences() {
        PreferenceGroup keyboardOptions = findPreference("keyboard-options");
        if (keyboardOptions == null) return;

        // These controls are XML-backed so they are visible both in SettingsFragment and in the
        // SmartCategorySettingsFragment used by User experience. Keep runtime creation only as a
        // migration/fallback for old or partially restored preference resources.
        ListPreference mode = findPreference(SearchEditText.PREF_SEARCH_KEYBOARD_MODE);
        if (mode == null) {
            mode = new ListPreference(requireContext());
            mode.setKey(SearchEditText.PREF_SEARCH_KEYBOARD_MODE);
            mode.setTitle("Search keyboard");
            mode.setEntries(new CharSequence[]{"Built-in Smart S keyboard", "System keyboard"});
            mode.setEntryValues(new CharSequence[]{
                    SearchEditText.KEYBOARD_MODE_BUILT_IN,
                    SearchEditText.KEYBOARD_MODE_SYSTEM
            });
            mode.setDefaultValue(SearchEditText.KEYBOARD_MODE_BUILT_IN);
            keyboardOptions.addPreference(mode);
        }
        mode.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());

        addKeyboardSeekBar(
                keyboardOptions,
                BuiltInKeyboardSizing.PREF_HEIGHT_PERCENT,
                "Built-in keyboard height (% of screen)",
                "Resize only the keyboard height. Hard maximum is 50% of the available screen height.",
                BuiltInKeyboardSizing.MIN_HEIGHT_PERCENT,
                BuiltInKeyboardSizing.MAX_HEIGHT_PERCENT,
                BuiltInKeyboardSizing.DEFAULT_HEIGHT_PERCENT);

        addKeyboardSeekBar(
                keyboardOptions,
                BuiltInKeyboardSizing.PREF_WIDTH_PERCENT,
                "Built-in keyboard width (% of screen)",
                "Resize the whole keyboard horizontally without changing its height, buttons or letter size.",
                BuiltInKeyboardSizing.MIN_WIDTH_PERCENT,
                BuiltInKeyboardSizing.MAX_WIDTH_PERCENT,
                BuiltInKeyboardSizing.DEFAULT_WIDTH_PERCENT);

        addKeyboardSeekBar(
                keyboardOptions,
                BuiltInKeyboardSizing.PREF_BUTTON_PERCENT,
                "Built-in keyboard button size (%)",
                "Resize key/button surfaces independently from keyboard width, height and letter size.",
                BuiltInKeyboardSizing.MIN_BUTTON_PERCENT,
                BuiltInKeyboardSizing.MAX_BUTTON_PERCENT,
                BuiltInKeyboardSizing.DEFAULT_BUTTON_PERCENT);

        addKeyboardSeekBar(
                keyboardOptions,
                BuiltInKeyboardSizing.PREF_LABEL_SIZE_SP,
                "Built-in keyboard letter size (sp)",
                "Resize letters and key labels independently from the physical button size.",
                BuiltInKeyboardSizing.MIN_LABEL_SIZE_SP,
                BuiltInKeyboardSizing.MAX_LABEL_SIZE_SP,
                BuiltInKeyboardSizing.DEFAULT_LABEL_SIZE_SP);

        Preference chooser = findPreference(PREF_CHOOSE_SYSTEM_KEYBOARD);
        if (chooser == null) {
            chooser = new Preference(requireContext());
            chooser.setKey(PREF_CHOOSE_SYSTEM_KEYBOARD);
            chooser.setTitle("Choose installed system keyboard");
            keyboardOptions.addPreference(chooser);
        }
        chooser.setOnPreferenceClickListener(preference -> {
            InputMethodManager imm = ContextCompat.getSystemService(
                    requireContext(), InputMethodManager.class);
            if (imm != null) imm.showInputMethodPicker();
            else Toast.makeText(
                    requireContext(),
                    "Android keyboard picker is unavailable",
                    Toast.LENGTH_SHORT).show();
            return true;
        });

        // XML-backed sliders also need the immediate-update behavior that the old dynamic controls
        // had in 3.30.153.
        String[] sizingKeys = {
                BuiltInKeyboardSizing.PREF_HEIGHT_PERCENT,
                BuiltInKeyboardSizing.PREF_WIDTH_PERCENT,
                BuiltInKeyboardSizing.PREF_BUTTON_PERCENT,
                BuiltInKeyboardSizing.PREF_LABEL_SIZE_SP
        };
        for (String key : sizingKeys) {
            SeekBarPreference slider = findPreference(key);
            if (slider != null) {
                slider.setSeekBarIncrement(1);
                slider.setShowSeekBarValue(true);
                slider.setUpdatesContinuously(true);
            }
        }

        refreshSearchKeyboardPicker();
    }

    private void addKeyboardSeekBar(PreferenceGroup parent,
                                    String key,
                                    String title,
                                    String summary,
                                    int min,
                                    int max,
                                    int defaultValue) {
        if (parent.findPreference(key) != null) return;

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
        parent.addPreference(slider);
    }

    private void refreshSearchKeyboardPicker() {
        Preference chooser = findPreference(PREF_CHOOSE_SYSTEM_KEYBOARD);
        if (chooser == null) return;
        boolean useSystem = SearchEditText.KEYBOARD_MODE_SYSTEM.equals(
                prefs.getString(SearchEditText.PREF_SEARCH_KEYBOARD_MODE,
                        SearchEditText.KEYBOARD_MODE_BUILT_IN));
        boolean useBuiltIn = !useSystem;

        chooser.setEnabled(useSystem);
        chooser.setSummary(useSystem
                ? "Tap to switch between keyboards installed and enabled in Android."
                : "Select System keyboard above to use an installed Android keyboard.");

        String[] builtInSizingKeys = {
                BuiltInKeyboardSizing.PREF_HEIGHT_PERCENT,
                BuiltInKeyboardSizing.PREF_WIDTH_PERCENT,
                BuiltInKeyboardSizing.PREF_BUTTON_PERCENT,
                BuiltInKeyboardSizing.PREF_LABEL_SIZE_SP
        };
        for (String key : builtInSizingKeys) {
            Preference sizing = findPreference(key);
            if (sizing != null) sizing.setEnabled(useBuiltIn);
        }
    }

    private void addSemanticSearchPreferences(@Nullable String rootKey) {
        // Semantic controls are XML-backed under Providers → Semantic search & app metadata so
        // they are always visible and searchable. This method only wires live actions/status.
        Preference semanticScreen = findPreference("semantic-search-screen");
        boolean insideSemanticScreen = "semantic-search-screen".equals(rootKey);
        if (semanticScreen == null && !insideSemanticScreen) return;

        Preference updateSources = findPreference("semantic-update-all-app-source-data");
        if (updateSources != null) {
            updateSources.setOnPreferenceClickListener(preference -> {
                boolean started = AppSourceMetadataUpdater.refreshAll(
                        requireContext(),
                        getDataHandler(),
                        prefs,
                        () -> {
                            if (!isAdded()) return;
                            refreshAppSourceStatus();
                            refreshSemanticIndexStatus();
                            Toast.makeText(
                                    requireContext(),
                                    "App metadata and descriptions update finished",
                                    Toast.LENGTH_SHORT).show();
                        });
                refreshAppSourceStatus();
                if (!started) {
                    Toast.makeText(
                            requireContext(),
                            "App metadata update is already running",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(
                            requireContext(),
                            "Updating app metadata and descriptions in the background…",
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        Preference rebuild = findPreference("semantic-hnsw-rebuild");
        if (rebuild != null) {
            rebuild.setOnPreferenceClickListener(preference -> {
                SemanticHnswIndex.getInstance().scheduleRebuild(getDataHandler(), prefs);
                refreshSemanticIndexStatus();
                Toast.makeText(
                        requireContext(),
                        "Rebuilding semantic HNSW index in the background…",
                        Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        Preference status = findPreference("semantic-hnsw-status");
        if (status != null) status.setSelectable(false);

        Preference sourceStatus = findPreference("semantic-app-source-status");
        if (sourceStatus != null) sourceStatus.setSelectable(false);

        Preference info = findPreference("semantic-model-info");
        if (info != null) {
            info.setSelectable(false);
            info.setSummary(SemanticEmbeddingScorer.MODEL_NAME
                    + " · on-device search · cached app descriptions · HNSW vectors built in the background");
        }

        refreshSemanticIndexStatus();
        refreshAppSourceStatus();
    }

    private void updateItemsToRun() {
        for (String key : PREF_LISTS_WITH_DEPENDENCY) {
            updateItemToRun(key);
        }
    }

    private void updateItemToRun(String key) {
        LaunchPojoSelectPreference preference = findPreference(key + "-launch-id");
        if (preference != null) {
            String value = prefs.getString(key, null);
            boolean isLaunchEnabled = "launch-pojo".equals(value);
            preference.setEnabled(isLaunchEnabled);
            preference.setVisible(isLaunchEnabled);
        }
    }

    private void removePreference(String parentKey, String key) {
        PreferenceGroup p = findPreference(parentKey);
        if (p != null) {
            Preference c = p.findPreference(key);
            if (c != null) {
                p.removePreference(c);
            } else {
                Log.d(TAG, "Preference to remove not found: " + parentKey + "/" + key);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        refreshSearchKeyboardPicker();
        refreshSemanticIndexStatus();
        refreshAppSourceStatus();
    }

    private void refreshAppSourceStatus() {
        Preference status = findPreference("semantic-app-source-status");
        if (status != null) {
            status.setSummary(AppSourceMetadataUpdater.statusSummary(requireContext()));
        }
        Preference update = findPreference("semantic-update-all-app-source-data");
        if (update != null) {
            update.setEnabled(!AppSourceMetadataUpdater.isRunning()
                    && prefs.getBoolean("semantic-search-enabled", false));
        }
    }

    private void refreshSemanticIndexStatus() {
        Preference status = findPreference("semantic-hnsw-status");
        if (status != null) {
            status.setSummary(SemanticHnswIndex.getInstance().statusSummary());
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key != null) {
            KissApplication.getApplication(requireContext()).getIconsHandler().onPrefChanged(sharedPreferences, key);

            if (SearchEditText.PREF_SEARCH_KEYBOARD_MODE.equals(key)) {
                refreshSearchKeyboardPicker();
            }

            if ("semantic-search-enabled".equals(key)
                    || "semantic-model".equals(key)
                    || "semantic-embedding-dimensions".equals(key)
                    || SemanticHnswIndex.PREF_HNSW_ENABLED.equals(key)
                    || AppSourceMetadataUpdater.PREF_USE_SOURCE_DESCRIPTIONS.equals(key)) {
                SemanticHnswIndex.getInstance().scheduleRebuild(getDataHandler(), sharedPreferences);
                refreshSemanticIndexStatus();
                refreshAppSourceStatus();
            } else if (SemanticHnswIndex.PREF_HNSW_EF_SEARCH.equals(key)) {
                refreshSemanticIndexStatus();
            }

            if (PREF_LISTS_WITH_DEPENDENCY.contains(key)) {
                updateItemToRun(key);
            }

            if (key.equalsIgnoreCase("available-search-providers")) {
                refreshSelectSearchProvider();
                refreshDefaultSearchProvider();
                getDataHandler().reloadSearchProvider();
            } else if (key.equalsIgnoreCase("selected-search-provider-names")) {
                refreshDefaultSearchProvider();
                getDataHandler().reloadSearchProvider();
            } else if (key.equalsIgnoreCase("enable-phone-history")) {
                boolean enabled = sharedPreferences.getBoolean(key, false);
                if (enabled) ensurePhoneHistoryPermissions(key);
                else setPhoneHistoryEnabled(false);
            } else if (key.equalsIgnoreCase("primary-color")) {
                UIColors.clearColorCache();
            } else if (key.equalsIgnoreCase("number-of-search-results")
                    || key.equalsIgnoreCase("number-of-display-elements")) {
                QuerySearcher.clearMaxResultCountCache();
            } else if (key.equalsIgnoreCase("default-search-provider")) {
                getDataHandler().reloadSearchProvider();
            } else if ("pref-fav-tags-list".equals(key)) {
                getDataHandler().reloadTags();

                // after we edit the fav tags list update DataHandler
                Set<String> favTags = sharedPreferences.getStringSet(key, Collections.emptySet());
                DataHandler dh = getDataHandler();
                List<Pojo> favoritesPojo = dh.getFavorites();
                for (Pojo pojo : favoritesPojo)
                    if (pojo instanceof TagDummyPojo && !favTags.contains(pojo.getName()))
                        dh.removeFromFavorites(pojo.id);
                for (String tagName : favTags)
                    dh.addToFavorites(TagsProvider.generateUniqueId(tagName));
            } else if ("exclude-favorites-apps".equals(key)) {
                getDataHandler().reloadApps();
            } else if ("enable-notification-history".equals(key)) {
                boolean enabled = sharedPreferences.getBoolean(key, false);
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                }
            } else if ("selected-contact-mime-types".equals(key)) {
                getDataHandler().reloadContactsProvider();
            } else if ("theme".equals(key)) {
                updateNightMode();
            } else if ("night-mode".equals(key)) {
                InterfaceTweaks.setDefaultNightMode(KissApplication.getApplication(requireContext()));
            }
        }
    }

    private void refreshSelectSearchProvider() {
        SelectCustomSearchProvidersPreference preference = findPreference("selected-search-provider-names");
        if (preference != null) {
            preference.refresh();
        }
    }

    private void refreshDefaultSearchProvider() {
        DefaultSearchProviderSelectPreference preference = findPreference("default-search-provider");
        if (preference != null) {
            preference.refresh();
        }
    }

    private void ensurePhoneHistoryPermissions(String preferenceKey) {
        if (!Permission.checkPermission(requireContext(), Permission.PERMISSION_READ_PHONE_STATE)) {
            Permission.askPermission(Permission.PERMISSION_READ_PHONE_STATE, new Permission.PermissionResultListener() {
                @Override
                public void onGranted() {
                    ensurePhoneHistoryPermissions(preferenceKey);
                }

                @Override
                public void onDenied() {
                    SwitchPreference p = findPreference(preferenceKey);
                    if (p != null) p.setChecked(false);
                    Toast.makeText(getContext(), R.string.permission_denied, Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        if (!Permission.checkPermission(requireContext(), Permission.PERMISSION_READ_CALL_LOG)) {
            Permission.askPermission(Permission.PERMISSION_READ_CALL_LOG, new Permission.PermissionResultListener() {
                @Override
                public void onGranted() {
                    setPhoneHistoryEnabled(true);
                }

                @Override
                public void onDenied() {
                    // Call screening still works without READ_CALL_LOG; only caller-name enrichment
                    // falls back to Contacts/number when Android does not grant the restricted permission.
                    setPhoneHistoryEnabled(true);
                    Toast.makeText(getContext(),
                            "Call log permission is needed for caller-ID names in phone history.",
                            Toast.LENGTH_LONG).show();
                }
            });
            return;
        }

        setPhoneHistoryEnabled(true);
    }

    protected void setPhoneHistoryEnabled(boolean enabled) {
        IncomingCallHandler.setEnabled(getContext(), enabled);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && enabled) {
            RoleManager roleManager = ContextCompat.getSystemService(requireContext(), RoleManager.class);
            Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING);
            startActivityForResult(intent, REQUEST_CALL_SCREENING_APP);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        permissionManager.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void fixSummaries() {
        int historyLength = getDataHandler().getHistoryLength();
        if (historyLength > 5) {
            Preference resetHistory = findPreference("reset-history");
            if (resetHistory != null) {
                resetHistory.setSummary(getString(R.string.items_title, historyLength));
            }
        }

        // Only display "rate the app" preference if the user has been using KISS long enough to enjoy it ;)
        Preference rateApp = findPreference("rate-app");
        if (rateApp != null) {
            if (historyLength < 300) {
                getPreferenceScreen().removePreference(rateApp);
            } else {
                rateApp.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse("market://details?id=" + getContext().getApplicationContext().getPackageName()));
                    startActivity(intent);

                    return true;
                });
            }
        }
    }

    private void updateNightMode() {
        boolean isAmoledTheme = "amoled-dark".equals(prefs.getString("theme", "transparent"));

        Preference darkMode = findPreference("night-mode");
        if (darkMode != null) {
            darkMode.setEnabled(!isAmoledTheme);
            darkMode.setVisible(!isAmoledTheme);
        }

        if (isAmoledTheme) {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                    .putString("night-mode", "yes").apply();
        }
    }

    /**
     * Override to catch an exception which can crash whole app.
     * This exception can occure when entries are added to/removed from preferences dynamically.
     *
     * @param key The key of the preference to retrieve.
     * @return The {@link Preference} with the key, or null.
     * @see PreferenceFragmentCompat#findPreference(CharSequence)
     */
    @Nullable
    @Override
    public <T extends Preference> T findPreference(@NonNull CharSequence key) {
        try {
            return super.findPreference(key);
        } catch (IndexOutOfBoundsException e) {
            Log.e(TAG, "Unable to find preference for key:" + key);
            return null;
        }
    }

    private DataHandler getDataHandler() {
        return KissApplication.getApplication(requireContext()).getDataHandler();
    }

    @Override
    public boolean onPreferenceDisplayDialog(@NonNull PreferenceFragmentCompat caller, @NonNull Preference pref) {
        DialogFragment dialogFragment = null;
        if (pref instanceof DialogShowingPreference) {
            dialogFragment = DialogShowingPreferenceDialogFragment.newInstance(pref.getKey(), this::onDialogClosed);
        } else if (pref instanceof ColorPreference) {
            dialogFragment = ColorPreferenceDialogFragment.newInstance(pref.getKey());
        } else if (pref instanceof AddSearchProviderPreference) {
            dialogFragment = AddSearchProviderPreferenceDialogFragment.newInstance(pref.getKey());
        }

        if (dialogFragment != null) {
            // check if dialog is already showing
            if (getParentFragmentManager().findFragmentByTag(DIALOG_FRAGMENT_TAG) != null) {
                return true;
            }
            dialogFragment.setTargetFragment(caller, 0);
            dialogFragment.show(getParentFragmentManager(), DIALOG_FRAGMENT_TAG);
            return true;
        }

        return false;
    }

    private void onDialogClosed(Preference pref, boolean positiveResult) {
        switch (pref.getKey()) {
            case "reset-history":
                if (positiveResult) {
                    KissApplication.getApplication(requireContext()).getDataHandler().clearHistory();
                    pref.setSummary(requireContext().getString(R.string.history_erased));
                    Toast.makeText(getContext(), R.string.history_erased, Toast.LENGTH_LONG).show();
                }
                break;
            case "reset-search-providers":
                if (positiveResult) {
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                            .remove("available-search-providers").apply();
                    KissApplication.getApplication(requireContext()).getDataHandler().reloadSearchProvider();
                    Toast.makeText(getContext(), R.string.search_provider_reset_done_desc, Toast.LENGTH_LONG).show();
                }
                break;
            case "reset-excluded-apps":
                if (positiveResult) {
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                            .putStringSet("excluded-apps", null).apply();
                    KissApplication.getApplication(requireContext()).getDataHandler().reloadApps();
                    Toast.makeText(getContext(), R.string.excluded_app_list_erased, Toast.LENGTH_LONG).show();
                }
                break;
            case "reset-excluded-from-history-apps":
                if (positiveResult) {
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                            .putStringSet("excluded-apps-from-history", null).apply();
                    KissApplication.getApplication(requireContext()).getDataHandler().reloadApps(); // reload because it's cached in AppPojo#excludedFromHistory
                    Toast.makeText(getContext(), R.string.excluded_app_list_erased, Toast.LENGTH_LONG).show();
                }
                break;
            case "reset-excluded-app-shortcuts":
                if (positiveResult) {
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                            .putStringSet(DataHandler.PREF_KEY_EXCLUDED_SHORTCUT_APPS, null).apply();
                    DataHandler dataHandler = KissApplication.getApplication(requireContext()).getDataHandler();
                    // Reload shortcuts to refresh the shortcuts shown in KISS
                    dataHandler.reloadShortcuts();
                    // Reload apps since the `AppPojo.isExcludedShortcuts` value also needs to be refreshed
                    dataHandler.reloadApps();
                    Toast.makeText(getContext(), R.string.excluded_app_list_erased, Toast.LENGTH_LONG).show();
                }
                break;
            case "reset-favorites":
                if (positiveResult) {
                    getDataHandler().resetFavorites();
                    Toast.makeText(getContext(), R.string.favorites_erased, Toast.LENGTH_LONG).show();
                }
                break;
            case "reset-shortcuts":
                if (positiveResult && android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Remove all shortcuts
                    ShortcutUtil.removeAllShortcuts(getContext());
                    // Build all shortcuts
                    ShortcutUtil.addAllShortcuts(getContext());
                    Toast.makeText(getContext(), R.string.regenerate_shortcuts_done, Toast.LENGTH_LONG).show();
                }
                break;
            case "enable-notifications":
                if (positiveResult && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                }
                break;
            case "default-launcher":
                new DefaultLauncherPreference().onDialogClosed(getContext(), positiveResult);
                break;
            case "export-settings":
                new ExportSettingsPreference().onDialogClosed(getContext(), positiveResult);
                break;
            case "import-settings":
                new ImportSettingsPreference().onDialogClosed(getContext(), positiveResult);
                break;
            case "restart":
                if (positiveResult) {
                    System.exit(0);
                }
                break;
        }
    }
}
