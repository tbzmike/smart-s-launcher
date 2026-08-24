package fr.neamar.kiss.loader;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Process;
import android.os.UserManager;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.TagsHandler;
import fr.neamar.kiss.db.AppCatalogRecord;
import fr.neamar.kiss.db.AppRecord;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.UserHandle;

public class LoadAppPojos extends LoadPojos<AppPojo> {

    public static final String PREF_INDEX_DISABLED_APPS = "index-disabled-apps";
    private static final String TAG = LoadAppPojos.class.getSimpleName();
    private final TagsHandler tagsHandler;

    public LoadAppPojos(Context context) {
        super(context, "app://");
        tagsHandler = KissApplication.getApplication(context).getDataHandler().getTagsHandler();
    }

    @Override
    protected List<AppPojo> doInBackground(Void... params) {
        long start = System.currentTimeMillis();
        List<AppPojo> apps = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();

        Context ctx = context.get();
        if (ctx == null) return apps;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        boolean indexDisabledApps = prefs.getBoolean(PREF_INDEX_DISABLED_APPS, true);
        Set<String> excludedAppList = KissApplication.getApplication(ctx).getDataHandler().getExcluded();
        Set<String> excludedFromHistoryAppList = KissApplication.getApplication(ctx).getDataHandler().getExcludedFromHistory();
        Set<String> excludedShortcutsAppList = KissApplication.getApplication(ctx).getDataHandler().getExcludedShortcutApps();

        UserManager manager = ContextCompat.getSystemService(ctx, UserManager.class);
        LauncherApps launcherApps = ContextCompat.getSystemService(ctx, LauncherApps.class);
        if (manager == null || launcherApps == null) return apps;

        for (android.os.UserHandle profile : manager.getUserProfiles()) {
            boolean isPrivateProfile = PackageManagerUtils.isPrivateProfile(launcherApps, profile);
            long serial = manager.getSerialNumberForUser(profile);
            UserHandle user = new UserHandle(serial, profile);
            for (LauncherActivityInfo activityInfo : launcherApps.getActivityList(null, profile)) {
                if (isCancelled()) break;
                ApplicationInfo appInfo = activityInfo.getApplicationInfo();
                String packageKey = packageKey(serial, appInfo.packageName);
                if (seenPackages.contains(packageKey)) continue;

                boolean disabled = PackageManagerUtils.isAppSuspended(appInfo) || isQuietModeEnabled(manager, profile);
                if (!disabled || !isPrivateProfile) {
                    AppPojo app = createPojo(user, appInfo.packageName, activityInfo.getName(), activityInfo.getLabel(), disabled,
                            excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList);
                    apps.add(app);
                    seenPackages.add(packageKey);
                    SmartStateStore.rememberApp(ctx, appInfo.packageName, activityInfo.getName(), activityInfo.getLabel().toString(), serial);
                }
            }
        }

        android.os.UserHandle currentProfile = Process.myUserHandle();
        long currentSerial = manager.getSerialNumberForUser(currentProfile);
        UserHandle currentUser = new UserHandle(currentSerial, currentProfile);
        PackageManager pm = ctx.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS;

        // Existing global disabled-component query. Keep it because it is cheap and works on many ROMs.
        List<ResolveInfo> disabledCandidates = pm.queryIntentActivities(launcherIntent, flags);
        for (ResolveInfo resolveInfo : disabledCandidates) {
            if (isCancelled()) break;
            addResolvedLauncherCandidate(ctx, apps, seenPackages, resolveInfo, currentSerial, currentUser,
                    excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList, pm);
        }

        if (indexDisabledApps) {
            // LauncherApps can hide packages disabled by IceBox/package-manager state. Enumerate every
            // installed package (including disabled ones), then ask PackageManager for that package's
            // launcher activity explicitly. Per-package queries recover apps that some ROMs omit from
            // the global launcher query while still respecting the real CATEGORY_LAUNCHER contract.
            List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ApplicationInfo info : installed) {
                if (isCancelled()) break;
                if (info == null || info.packageName == null) continue;
                String packageKey = packageKey(currentSerial, info.packageName);
                if (seenPackages.contains(packageKey)) continue;
                if (!isPackageDisabled(pm, info)) continue;

                Intent perPackage = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(info.packageName);
                List<ResolveInfo> packageLaunchers = pm.queryIntentActivities(perPackage, flags);
                if (packageLaunchers == null || packageLaunchers.isEmpty()) continue;

                // Preserve the existing one-canonical-app-per-package model. Prefer an exported
                // launcher activity and ignore aliases/internal non-exported components.
                ResolveInfo chosen = null;
                for (ResolveInfo candidate : packageLaunchers) {
                    if (candidate != null && candidate.activityInfo != null && candidate.activityInfo.exported) {
                        chosen = candidate;
                        break;
                    }
                }
                if (chosen != null) {
                    addResolvedLauncherCandidate(ctx, apps, seenPackages, chosen, currentSerial, currentUser,
                            excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList, pm);
                }
            }
        }

        // Persistent catalog is the final safety net. IceBox can hide a disabled package from both
        // LauncherApps and launcher-intent queries. Installed-but-hidden is a frozen state, never an
        // uninstall: retain the exact remembered app://package/activity identity.
        for (AppCatalogRecord remembered : SmartStateStore.getRememberedApps(ctx, currentSerial)) {
            String packageKey = packageKey(currentSerial, remembered.packageName);
            if (seenPackages.contains(packageKey)) continue;

            final ApplicationInfo info;
            try {
                info = pm.getApplicationInfo(remembered.packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            } catch (PackageManager.NameNotFoundException e) {
                SmartStateStore.forgetPackage(ctx, remembered.packageName);
                continue;
            }

            boolean packageEnabled = !isPackageDisabled(pm, info);
            boolean activityVisible = false;
            try {
                ActivityInfo activityInfo = pm.getActivityInfo(
                        new android.content.ComponentName(remembered.packageName, remembered.activityName),
                        PackageManager.MATCH_DISABLED_COMPONENTS);
                activityVisible = activityInfo.exported && activityInfo.enabled;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Keep the remembered component when package visibility hides the activity.
            }

            AppPojo app = createPojo(currentUser, remembered.packageName, remembered.activityName,
                    remembered.label, !(packageEnabled && activityVisible),
                    excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList);
            app.setDisabled(true);
            apps.add(app);
            seenPackages.add(packageKey);
        }

        Map<String, AppRecord> customApps = DBHelper.getCustomAppData(ctx);
        for (AppPojo app : apps) {
            AppRecord customApp = customApps.get(app.getComponentName());
            if (customApp != null && customApp.hasCustomName()) app.setName(customApp.name);
        }

        Log.i(TAG, (System.currentTimeMillis() - start) + " milliseconds to list canonical apps including frozen catalog");
        return apps;
    }

    private void addResolvedLauncherCandidate(Context ctx, List<AppPojo> apps, Set<String> seenPackages,
                                              ResolveInfo resolveInfo, long serial, UserHandle user,
                                              Set<String> excludedAppList,
                                              Set<String> excludedFromHistoryAppList,
                                              Set<String> excludedShortcutsAppList,
                                              PackageManager pm) {
        ActivityInfo activity = resolveInfo == null ? null : resolveInfo.activityInfo;
        if (activity == null || activity.applicationInfo == null || !activity.exported) return;
        String packageKey = packageKey(serial, activity.packageName);
        if (seenPackages.contains(packageKey)) return;

        CharSequence label = resolveInfo.loadLabel(pm);
        if (label == null || label.length() == 0) label = activity.applicationInfo.loadLabel(pm);
        boolean disabled = isPackageDisabled(pm, activity.applicationInfo) || !activity.enabled;
        AppPojo app = createPojo(user, activity.packageName, activity.name,
                label == null ? activity.packageName : label, disabled,
                excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList);
        apps.add(app);
        seenPackages.add(packageKey);
        SmartStateStore.rememberApp(ctx, activity.packageName, activity.name, app.getName(), serial);
    }

    private boolean isPackageDisabled(PackageManager pm, ApplicationInfo info) {
        boolean enabled = info.enabled && !PackageManagerUtils.isAppSuspended(info);
        try {
            int state = pm.getApplicationEnabledSetting(info.packageName);
            enabled = enabled
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (IllegalArgumentException ignored) {
            enabled = false;
        }
        return !enabled;
    }

    private String packageKey(long serial, String packageName) {
        return serial + "|" + packageName;
    }

    private boolean isQuietModeEnabled(UserManager manager, android.os.UserHandle profile) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && manager.isQuietModeEnabled(profile);
    }

    private AppPojo createPojo(UserHandle userHandle, String packageName, String activityName, CharSequence label,
                               boolean disabled, Set<String> excludedAppList,
                               Set<String> excludedFromHistoryAppList, Set<String> excludedShortcutsAppList) {
        String id = userHandle.addUserSuffixToString(pojoScheme + packageName + "/" + activityName, '/');
        boolean isExcluded = excludedAppList.contains(AppPojo.getComponentName(packageName, activityName, userHandle));
        boolean isExcludedFromHistory = excludedFromHistoryAppList.contains(id);
        boolean isExcludedShortcuts = excludedShortcutsAppList.contains(packageName);
        AppPojo app = new AppPojo(id, packageName, activityName, userHandle,
                isExcluded, isExcludedFromHistory, isExcludedShortcuts, disabled);
        app.setName(label == null ? packageName : label.toString());
        app.setTags(tagsHandler.getTags(app.id));
        return app;
    }
}
