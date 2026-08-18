package fr.neamar.kiss.dataprovider.simpleprovider;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

import androidx.annotation.DrawableRes;
import androidx.preference.PreferenceManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import fr.neamar.kiss.R;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.fuzzy.MatchInfo;
import fr.neamar.kiss.utils.fuzzy.SmartMatcher;

public class SettingsProvider extends SimpleProvider<SettingPojo> {
    private final static String SCHEME = "setting://";
    private final String settingName;
    private final List<SettingPojo> pojos;
    private final WeakReference<Context> contextReference;
    private final InstalledFeatureProvider installedFeatureProvider;

    public SettingsProvider(Context context) {
        pojos = new ArrayList<>();
        PackageManager pm = context.getPackageManager();

        addIfResolvable(context, context.getString(R.string.settings_airplane), Settings.ACTION_AIRPLANE_MODE_SETTINGS, R.drawable.setting_airplane);
        addIfResolvable(context, context.getString(R.string.settings_device_info), Settings.ACTION_DEVICE_INFO_SETTINGS, R.drawable.setting_info);
        addIfResolvable(context, context.getString(R.string.settings_applications), Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, R.drawable.setting_apps);
        addIfResolvable(context, context.getString(R.string.settings_connectivity), Settings.ACTION_WIRELESS_SETTINGS, R.drawable.setting_wifi);
        addIfResolvable(context, context.getString(R.string.settings_storage), Settings.ACTION_INTERNAL_STORAGE_SETTINGS, R.drawable.setting_storage);
        addIfResolvable(context, context.getString(R.string.settings_accessibility), Settings.ACTION_ACCESSIBILITY_SETTINGS, R.drawable.setting_accessibility);
        addIfResolvable(context, context.getString(R.string.settings_battery), Intent.ACTION_POWER_USAGE_SUMMARY, R.drawable.setting_battery);
        addExplicitIfResolvable(context, context.getString(R.string.settings_tethering), "com.android.settings", "com.android.settings.TetherSettings", R.drawable.setting_tethering);
        addIfResolvable(context, context.getString(R.string.settings_sound), Settings.ACTION_SOUND_SETTINGS, R.drawable.setting_volume);
        addIfResolvable(context, context.getString(R.string.settings_display), Settings.ACTION_DISPLAY_SETTINGS, R.drawable.setting_display);
        if (pm.hasSystemFeature(PackageManager.FEATURE_NFC)) addIfResolvable(context, context.getString(R.string.settings_nfc), Settings.ACTION_NFC_SETTINGS, R.drawable.setting_nfc);
        addIfResolvable(context, context.getString(R.string.settings_dev), Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, R.drawable.setting_dev);

        // Additional public Android settings entry points. Every action is resolve-checked before
        // being indexed, so OEMs that do not expose a screen simply won't show that result.
        addIfResolvable(context, "Wi‑Fi settings", Settings.ACTION_WIFI_SETTINGS, R.drawable.setting_wifi);
        addIfResolvable(context, "Bluetooth settings", Settings.ACTION_BLUETOOTH_SETTINGS, R.drawable.setting_wifi);
        addIfResolvable(context, "Mobile network settings", Settings.ACTION_DATA_ROAMING_SETTINGS, R.drawable.setting_wifi);
        addIfResolvable(context, "Location settings", Settings.ACTION_LOCATION_SOURCE_SETTINGS, R.drawable.setting_wifi);
        addIfResolvable(context, "Security settings", Settings.ACTION_SECURITY_SETTINGS, R.drawable.setting_apps);
        addIfResolvable(context, "Date & time settings", Settings.ACTION_DATE_SETTINGS, R.drawable.setting_info);
        addIfResolvable(context, "Keyboard & input settings", Settings.ACTION_INPUT_METHOD_SETTINGS, R.drawable.setting_apps);
        addIfResolvable(context, "Notification access", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, R.drawable.setting_apps);
        addIfResolvable(context, "Battery saver settings", Settings.ACTION_BATTERY_SAVER_SETTINGS, R.drawable.setting_battery);
        addIfResolvable(context, "Battery optimization", Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, R.drawable.setting_battery);
        addIfResolvable(context, "Default apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, R.drawable.setting_apps);
        addIfResolvable(context, "Display over other apps", Settings.ACTION_MANAGE_OVERLAY_PERMISSION, R.drawable.setting_apps);
        addIfResolvable(context, "Modify system settings", Settings.ACTION_MANAGE_WRITE_SETTINGS, R.drawable.setting_apps);
        addIfResolvable(context, "App notification settings", Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS, R.drawable.setting_apps);

        settingName = context.getString(R.string.settings_prefix).toLowerCase(Locale.ROOT);
        contextReference = new WeakReference<>(context);
        installedFeatureProvider = new InstalledFeatureProvider(context);
    }

    private void addIfResolvable(Context context, String name, String action, @DrawableRes int resId) {
        Intent intent = new Intent(action);
        if (context.getPackageManager().resolveActivity(intent, 0) != null) {
            pojos.add(createPojo(name, action, resId));
        }
    }

    private void addExplicitIfResolvable(Context context, String name, String packageName, String className, @DrawableRes int resId) {
        Intent intent = new Intent().setClassName(packageName, className);
        if (context.getPackageManager().resolveActivity(intent, 0) != null) {
            pojos.add(createPojo(name, packageName, className, resId));
        }
    }

    private void assignName(SettingPojo pojo, String name) { pojo.setName(name, true); }
    private String getId(String settingName) { return SCHEME + settingName.toLowerCase(Locale.ENGLISH); }
    private SettingPojo createPojo(String name, String packageName, String settingName, @DrawableRes int resId) {
        SettingPojo pojo = new SettingPojo(getId(settingName), settingName, packageName, resId); assignName(pojo, name); return pojo;
    }
    private SettingPojo createPojo(String name, String settingName, @DrawableRes int resId) {
        SettingPojo pojo = new SettingPojo(getId(settingName), settingName, resId); assignName(pojo, name); return pojo;
    }

    @Override
    public void requestResults(String query, Searcher searcher) {
        Context context = contextReference.get();
        if (context == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean("enable-settings", true)) return;

        for (SettingPojo pojo : pojos) {
            MatchInfo matchInfo = SmartMatcher.match(context, query, pojo.normalizedName, pojo.getName());
            boolean match = pojo.updateMatchingRelevance(matchInfo, false);
            if (!match) {
                // Keep the localized generic "settings" prefix searchable for settings only.
                matchInfo = fr.neamar.kiss.utils.fuzzy.TypoTolerance.match(context, query, settingName);
                match = pojo.updateMatchingRelevance(matchInfo, match);
            }
            if (match && !searcher.addResult(pojo)) return;
        }

        // App/system deep features are a separate cache but share the existing SettingPojo launch
        // path. This keeps explicit activity launch resolve-checked and avoids a new result type.
        installedFeatureProvider.requestResults(query, searcher);
    }

    public boolean mayFindById(String id) { return id.startsWith(SCHEME); }
}
