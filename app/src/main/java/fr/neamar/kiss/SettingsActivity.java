package fr.neamar.kiss;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import java.util.Arrays;
import java.util.List;

import fr.neamar.kiss.forwarder.ExperienceTweaks;
import fr.neamar.kiss.forwarder.InterfaceTweaks;
import fr.neamar.kiss.preference.UiEditLock;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.SystemUiVisibilityHelper;

public class SettingsActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener, PreferenceFragmentCompat.OnPreferenceStartScreenCallback, FragmentManager.OnBackStackChangedListener, PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private static final String TAG = SettingsActivity.class.getSimpleName();
    public static final String ARG_SHOW_FRAGMENT = "show_fragment";

    private static final List<String> SETTINGS_REQUIRING_RESTART = Arrays.asList("primary-color", "transparent-search", "transparent-favorites",
            "pref-rounded-list", "pref-rounded-bars", "pref-swap-kiss-button-with-menu", "pref-hide-circle", "history-hide",
            "enable-favorites-bar", "notification-bar-color", "black-notification-icons", "icons-pack", "theme-shadow",
            "theme-separator", "theme-result-color", "large-favorites-bar", "pref-hide-search-bar-hint", "theme-wallpaper",
            "theme-bar-color", "results-size", "large-result-list-margins", "themed-icons", "icons-hide",
            "pref-fav-tags-drawable", null);
    private static final List<String> SETTINGS_REQUIRING_RESTART_FOR_SETTINGS_ACTIVITY = Arrays.asList("theme", "force-portrait", "night-mode", null);

    private boolean requireFullRestart = false;
    private SharedPreferences prefs;
    private SystemUiVisibilityHelper systemUiVisibilityHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        InterfaceTweaks.applySettingsTheme(this, prefs);
        systemUiVisibilityHelper = new SystemUiVisibilityHelper(this);
        ExperienceTweaks.setRequestedOrientation(this, prefs);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_toolbar_content);

        Toolbar toolbar = findViewById(R.id.main_toolbar);
        // Keep the overflow popup readable regardless of the active launcher/day-night theme.
        // The popup itself uses an explicit high-contrast theme instead of inheriting a
        // translucent/light background with light text from the launcher theme.
        toolbar.setPopupTheme(R.style.SettingsPopupTheme);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        getSupportFragmentManager().addOnBackStackChangedListener(this);
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(ARG_SHOW_FRAGMENT);
        if (fragment == null) fragment = new SettingsFragment();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content_container, fragment, ARG_SHOW_FRAGMENT)
                .commit();
    }

    @Override
    protected void onTitleChanged(CharSequence title, int color) {
        super.onTitleChanged(title, color);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setTitle(title);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_settings, menu);
        MenuItem unlock = menu.findItem(R.id.unlock_ui);
        if (unlock != null) unlock.setVisible(UiEditLock.isLocked(this));
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.unlock_ui) {
            UiEditLock.unlock(this);
            invalidateOptionsMenu();
            Toast.makeText(this, "Launcher UI unlocked", Toast.LENGTH_SHORT).show();
            Fragment current = getSupportFragmentManager().findFragmentByTag(ARG_SHOW_FRAGMENT);
            if (current instanceof PreferenceFragmentCompat) {
                PreferenceScreen screen = ((PreferenceFragmentCompat) current).getPreferenceScreen();
                if (screen != null) UiEditLock.refresh(this, screen);
            }
            return true;
        }
        if (item.getItemId() == R.id.battery_monitor) {
            startActivity(new Intent(this, BatteryMonitorActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.indexing_settings) {
            startActivity(new Intent(this, IndexingSettingsActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.smart_features) {
            openSmartFeaturesSettings();
            return true;
        }
        if (item.getItemId() == R.id.help) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("http://help.kisslauncher.com"));
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openSmartFeaturesSettings() {
        Fragment current = getSupportFragmentManager().findFragmentByTag(ARG_SHOW_FRAGMENT);
        if (current instanceof SmartFeaturesSettingsFragment) return;
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content_container, new SmartFeaturesSettingsFragment(), ARG_SHOW_FRAGMENT)
                .addToBackStack("smart-features")
                .commit();
    }

    @Override
    public void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        invalidateOptionsMenu();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (UiEditLock.PREF_KEY.equals(key)) invalidateOptionsMenu();
        if (SETTINGS_REQUIRING_RESTART.contains(key) || SETTINGS_REQUIRING_RESTART_FOR_SETTINGS_ACTIVITY.contains(key)) {
            requireFullRestart = true;
            if (SETTINGS_REQUIRING_RESTART_FOR_SETTINGS_ACTIVITY.contains(key)) recreate();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (requireFullRestart) {
            prefs.edit().putBoolean("require-layout-update", true).apply();
            requireFullRestart = false;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        systemUiVisibilityHelper.onWindowFocusChanged(hasFocus);
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (getSupportFragmentManager().popBackStackImmediate()) return true;
        return super.onSupportNavigateUp();
    }

    @Override
    public boolean onPreferenceStartScreen(@NonNull PreferenceFragmentCompat caller, @NonNull PreferenceScreen pref) {
        final Bundle args = new Bundle(pref.getExtras());
        String key = pref.getKey();
        if (!TextUtils.isEmpty(key)) {
            args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, key);
            SettingsFragment fragment = isSmartCategory(key)
                    ? new SmartCategorySettingsFragment()
                    : new SettingsFragment();
            fragment.setArguments(args);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content_container, fragment, ARG_SHOW_FRAGMENT)
                    .addToBackStack(key)
                    .commit();
        } else {
            Log.w(TAG, "No key set for preference screen!");
            caller.setPreferenceScreen(pref);
            setTitle(pref.getTitle());
        }
        return true;
    }

    private boolean isSmartCategory(String key) {
        return "history_category".equals(key)
                || "ui-holder".equals(key)
                || "theme-customisation".equals(key)
                || "ux-holder".equals(key)
                || "advanced".equals(key);
    }

    @Override
    public void onBackStackChanged() {
        CharSequence title = null;
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(ARG_SHOW_FRAGMENT);
        if (fragment instanceof PreferenceFragmentCompat) {
            Preference preference = ((PreferenceFragmentCompat) fragment).getPreferenceScreen();
            if (preference != null) title = preference.getTitle();
        }
        if (title != null) setTitle(title);
        else setTitle(R.string.activity_setting);
    }

    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller, @NonNull Preference pref) {
        if (pref.getFragment() == null) {
            Log.w(TAG, "No fragment set for preference!");
            return false;
        }
        final Bundle args = pref.getExtras();
        String key = pref.getKey();
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, key);
        final Fragment fragment = getSupportFragmentManager().getFragmentFactory().instantiate(
                getClassLoader(), pref.getFragment());
        fragment.setArguments(args);
        fragment.setTargetFragment(caller, 0);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content_container, fragment, ARG_SHOW_FRAGMENT)
                .addToBackStack(key)
                .commit();
        return true;
    }
}
