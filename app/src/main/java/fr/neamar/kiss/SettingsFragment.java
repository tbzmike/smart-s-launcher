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
import fr.neamar.kiss.searcher.QuerySearcher;
import fr.neamar.kiss.searcher.SemanticEmbeddingScorer;
import fr.neamar.kiss.ui.SearchEditText;
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
        try {
            addSearchKeyboardPreferences();
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to create search keyboard settings; keeping core settings available", e);
        }
        try {
            addSemanticSearchPreferences(rootKey);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to create semantic search settings; keeping core settings available", e);
        }

        if (prefs.getStringSet("selected-search-provider-names", null) == null) {
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

    private void addSearchKeyboardPreferences() {
        Preference displayKeyboard = findPreference("display-keyboard");
        if (displayKeyboard == null || !(displayKeyboard.getParent() instanceof PreferenceGroup)) {
            return;
        }
        PreferenceGroup keyboardOptions = displayKeyboard.getParent();

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
            mode.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            mode.setOrder(displayKeyboard.getOrder() - 2);
            keyboardOptions.addPreference(mode);
        }

        Preference chooser = findPreference(PREF_CHOOSE_SYSTEM_KEYBOARD);
        if (chooser == null) {
            chooser = new Preference(requireContext());
            chooser.setKey(PREF_CHOOSE_SYSTEM_KEYBOARD);
            chooser.setTitle("Choose installed system keyboard");
            chooser.setSummary("Open Android's keyboard picker to switch between installed keyboards.");
            chooser.setOrder(displayKeyboard.getOrder() - 1);
            chooser.setOnPreferenceClickListener(preference -> {
                InputMethodManager imm = ContextCompat.getSystemService(requireContext(), InputMethodManager.class);
                if (imm != null) {
                    imm.showInputMethodPicker();
                    return true;
                }
                Toast.makeText(requireContext(), "Android keyboard picker is unavailable", Toast.LENGTH_SHORT).show();
                return true;
            });
            keyboardOptions.addPreference(chooser);
        }

        refreshSearchKeyboardPicker();
    }

    private void refreshSearchKeyboardPicker() {
        Preference chooser = findPreference(PREF_CHOOSE_SYSTEM_KEYBOARD);
        if (chooser == null) return;
        boolean useSystem = SearchEditText.KEYBOARD_MODE_SYSTEM.equals(
                prefs.getString(SearchEditText.PREF_SEARCH_KEYBOARD_MODE,
                        SearchEditText.KEYBOARD_MODE_BUILT_IN));
        chooser.setEnabled(useSystem);
        chooser.setSummary(useSystem
                ? "Tap to switch between keyboards installed and enabled in Android."
                : "Select System keyboard above to use an installed Android keyboard.");
    }

    private void addSemanticSearchPreferences(@Nullable String rootKey) {
        PreferenceGroup parent = findPreference("providers");
        if (parent == null && "providers".equals(rootKey)) parent = getPreferenceScreen();
        if (parent == null || parent.findPreference("semantic-search-category") != null) return;

        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setKey("semantic-search-category");
        category.setTitle("Semantic search & embeddings");
        parent.addPreference(category);

        SwitchPreference enabled = new SwitchPreference(requireContext());
        enabled.setKey("semantic-search-enabled");
        enabled.setTitle("Enable semantic search");
        enabled.setSummary("Use on-device embeddings as a fallback when literal/fuzzy matching is not enough.");
        enabled.setDefaultValue(false);
        category.addPreference(enabled);

        ListPreference model = new ListPreference(requireContext());
        model.setKey("semantic-model");
        model.setTitle("Embedding model");
        model.setEntries(new CharSequence[]{SemanticEmbeddingScorer.MODEL_NAME});
        model.setEntryValues(new CharSequence[]{SemanticEmbeddingScorer.MODEL_ID});
        model.setDefaultValue(SemanticEmbeddingScorer.MODEL_ID);
        model.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        model.setDependency("semantic-search-enabled");
        category.addPreference(model);

        ListPreference dimensions = new ListPreference(requireContext());
        dimensions.setKey("semantic-embedding-dimensions");
        dimensions.setTitle("Embedding dimensions");
        dimensions.setEntries(new CharSequence[]{"64 · fastest", "128 · balanced", "256 · richer"});
        dimensions.setEntryValues(new CharSequence[]{"64", "128", "256"});
        dimensions.setDefaultValue("128");
        dimensions.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        dimensions.setDependency("semantic-search-enabled");
        category.addPreference(dimensions);

        ListPreference threshold = new ListPreference(requireContext());
        threshold.setKey("semantic-threshold");
        threshold.setTitle("Semantic similarity threshold");
        threshold.setEntries(new CharSequence[]{"0.26 · broad", "0.34 · balanced", "0.42 · strict", "0.52 · very strict"});
        threshold.setEntryValues(new CharSequence[]{"0.26", "0.34", "0.42", "0.52"});
        threshold.setDefaultValue("0.34");
        threshold.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        threshold.setDependency("semantic-search-enabled");
        category.addPreference(threshold);

        Preference info = new Preference(requireContext());
        info.setKey("semantic-model-info");
        info.setTitle("Embedding engine details");
        info.setSummary(SemanticEmbeddingScorer.MODEL_NAME + " · on-device · no network · vectors computed at search time");
        info.setSelectable(false);
        category.addPreference(info);
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
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key != null) {
            KissApplication.getApplication(requireContext()).getIconsHandler().onPrefChanged(sharedPreferences, key);

            if (SearchEditText.PREF_SEARCH_KEYBOARD_MODE.equals(key)) {
                refreshSearchKeyboardPicker();
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
            dialogFragment.setTargetFragment(this, 0);
            dialogFragment.show(getParentFragmentManager(), DIALOG_FRAGMENT_TAG);
            return true;
        }
        return false;
    }

    private void onDialogClosed(@NonNull DialogShowingPreference preference, boolean positiveResult) {
        if (positiveResult) {
            preference.onDialogClosed(true);
        }
    }
}
