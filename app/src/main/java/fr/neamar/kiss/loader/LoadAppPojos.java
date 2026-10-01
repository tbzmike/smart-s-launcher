package fr.neamar.kiss.loader;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Process;
import android.os.UserManager;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import fr.neamar.kiss.utils.FrozenAppPreferences;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.LauncherScrollWorkGate;
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
        // PackageManager/LauncherApps enumeration can take >1s on large installs. Keep it at
        // background scheduling priority so it cannot steal CPU time from launcher frames.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        if (!waitForScrollIdle()) return new ArrayList<>();
        long start = System.currentTimeMillis();
        List<AppPojo> apps = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();

        Context ctx = context.get();
        if (ctx == null) return apps;

        boolean indexDisabledApps = FrozenAppPreferences.detect(ctx);
        Set<String> excludedAppList = KissApplication.getApplication(ctx).getDataHandler().getExcluded();
        Set<String> excludedFromHistoryAppList = KissApplication.getApplication(ctx).getDataHandler().getExcludedFromHistory();
        Set<String> excludedShortcutsAppList = KissApplication.getApplication(ctx).getDataHandler().getExcludedShortcutApps();

        UserManager manager = ContextCompat.getSystemService(ctx, UserManager.class);
        LauncherApps launcherApps = ContextCompat.getSystemService(ctx, LauncherApps.class);
        if (manager == null || launcherApps == null) return apps;

        // Load the persistent catalog once per profile. Provider reloads used to UPDATE/DELETE the
        // same app_catalog rows for every installed app on every scan even when nothing changed,
        // creating avoidable SQLite work and allocation/GC pressure.
        Map<Long, Map<String, AppCatalogRecord>> rememberedBySerial = new LinkedHashMap<>();

        for (android.os.UserHandle profile : manager.getUserProfiles()) {
            if (!waitForScrollIdle()) break;
            boolean isPrivateProfile = PackageManagerUtils.isPrivateProfile(launcherApps, profile);
            long serial = manager.getSerialNumberForUser(profile);
            UserHandle user = new UserHandle(serial, profile);
            Map<String, AppCatalogRecord> rememberedForProfile =
                    rememberedForProfile(ctx, serial, rememberedBySerial);
            if (!waitForScrollIdle()) break;
            for (LauncherActivityInfo activityInfo : launcherApps.getActivityList(null, profile)) {
                if (isCancelled() || !waitForScrollIdle()) break;
                ApplicationInfo appInfo = activityInfo.getApplicationInfo();
                String packageKey = packageKey(serial, appInfo.packageName);
                if (seenPackages.contains(packageKey)) continue;

                boolean disabled = indexDisabledApps
                        && (PackageManagerUtils.isAppSuspended(appInfo) || isQuietModeEnabled(manager, profile));
                if (!disabled || !isPrivateProfile) {
                    AppPojo app = createPojo(user, appInfo.packageName, activityInfo.getName(), activityInfo.getLabel(), disabled,
                            excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList);
                    apps.add(app);
                    seenPackages.add(packageKey);
                    rememberAppIfChanged(ctx, app, serial, rememberedForProfile);
                }
            }
        }

        android.os.UserHandle currentProfile = Process.myUserHandle();
        long currentSerial = manager.getSerialNumberForUser(currentProfile);
        UserHandle currentUser = new UserHandle(currentSerial, currentProfile);
        Map<String, AppCatalogRecord> currentRemembered =
                rememberedForProfile(ctx, currentSerial, rememberedBySerial);
        PackageManager pm = ctx.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = indexDisabledApps ? PackageManager.MATCH_DISABLED_COMPONENTS : 0;

        // Existing global disabled-component query. Keep it because it is cheap and works on many ROMs.
        if (!waitForScrollIdle()) return apps;
        List<ResolveInfo> disabledCandidates = pm.queryIntentActivities(launcherIntent, flags);
        for (ResolveInfo resolveInfo : disabledCandidates) {
            if (isCancelled()) break;
            addResolvedLauncherCandidate(ctx, apps, seenPackages, resolveInfo, currentSerial, currentUser,
                    excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList, pm,
                    currentRemembered);
        }

        if (indexDisabledApps) {
            // LauncherApps can hide packages disabled by IceBox/package-manager state. Enumerate every
            // installed package (including disabled ones), then ask PackageManager for that package's
            // launcher activity explicitly. Per-package queries recover apps that some ROMs omit from
            // the global launcher query while still respecting the real CATEGORY_LAUNCHER contract.
            if (!waitForScrollIdle()) return apps;
            List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ApplicationInfo info : installed) {
                if (isCancelled() || !waitForScrollIdle()) break;
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
                            excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList, pm,
                            currentRemembered);
                }
            }
        }

        if (indexDisabledApps) {
            // Persistent catalog is the final safety net. IceBox can hide a disabled package from both
            // LauncherApps and launcher-intent queries. Installed-but-hidden is a frozen state, never an
            // uninstall: retain the exact remembered app://package/activity identity.
            for (AppCatalogRecord remembered : new ArrayList<>(currentRemembered.values())) {
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
                                              PackageManager pm,
                                              Map<String, AppCatalogRecord> rememberedForProfile) {
        ActivityInfo activity = resolveInfo == null ? null : resolveInfo.activityInfo;
        if (activity == null || activity.applicationInfo == null || !activity.exported) return;
        String packageKey = packageKey(serial, activity.packageName);
        if (seenPackages.contains(packageKey)) return;

        CharSequence label = null;
        try {
            label = resolveInfo.loadLabel(pm);
        } catch (RuntimeException ignored) {
            // Some installed apps advertise a stale/broken label resource. Falling back avoids
            // repeated Resources$NotFoundException stack construction during every app scan.
        }
        if (label == null || label.length() == 0) {
            try {
                label = activity.applicationInfo.loadLabel(pm);
            } catch (RuntimeException ignored) {
                label = activity.packageName;
            }
        }
        boolean disabled = isPackageDisabled(pm, activity.applicationInfo) || !activity.enabled;
        AppPojo app = createPojo(user, activity.packageName, activity.name,
                label == null ? activity.packageName : label, disabled,
                excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList);
        apps.add(app);
        seenPackages.add(packageKey);
        rememberAppIfChanged(ctx, app, serial, rememberedForProfile);
    }

    private boolean waitForScrollIdle() {
        while (LauncherScrollWorkGate.isScrolling() && !isCancelled()) {
            try {
                Thread.sleep(40L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isCancelled();
    }

    private Map<String, AppCatalogRecord> rememberedForProfile(
            Context context, long serial,
            Map<Long, Map<String, AppCatalogRecord>> rememberedBySerial) {
        Map<String, AppCatalogRecord> existing = rememberedBySerial.get(serial);
        if (existing != null) return existing;

        Map<String, AppCatalogRecord> indexed = new LinkedHashMap<>();
        for (AppCatalogRecord record : SmartStateStore.getRememberedApps(context, serial)) {
            if (record != null && !TextUtils.isEmpty(record.packageName)) {
                indexed.put(record.packageName, record);
            }
        }
        rememberedBySerial.put(serial, indexed);
        return indexed;
    }

    private void rememberAppIfChanged(Context context, AppPojo app, long serial,
                                      Map<String, AppCatalogRecord> remembered) {
        if (app == null || TextUtils.isEmpty(app.packageName)
                || TextUtils.isEmpty(app.activityName)) return;

        String label = TextUtils.isEmpty(app.getName()) ? app.packageName : app.getName();
        AppCatalogRecord previous = remembered.get(app.packageName);
        if (previous != null
                && TextUtils.equals(previous.activityName, app.activityName)
                && TextUtils.equals(previous.label, label)
                && previous.userSerial == serial) {
            return;
        }

        SmartStateStore.rememberApp(
                context, app.packageName, app.activityName, label, serial);
        AppCatalogRecord updated = new AppCatalogRecord();
        updated.packageName = app.packageName;
        updated.activityName = app.activityName;
        updated.label = label;
        updated.userSerial = serial;
        remembered.put(app.packageName, updated);
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
