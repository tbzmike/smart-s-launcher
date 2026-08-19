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

import androidx.core.content.ContextCompat;

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
        Set<String> seen = new HashSet<>();

        Context ctx = context.get();
        if (ctx == null) return apps;

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
                boolean disabled = PackageManagerUtils.isAppSuspended(appInfo) || isQuietModeEnabled(manager, profile);
                if (!disabled || !isPrivateProfile) {
                    AppPojo app = createPojo(user, appInfo.packageName, activityInfo.getName(), activityInfo.getLabel(), disabled,
                            excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList);
                    apps.add(app);
                    seen.add(key(serial, appInfo.packageName, activityInfo.getName()));
                    SmartStateStore.rememberApp(ctx, appInfo.packageName, activityInfo.getName(), activityInfo.getLabel().toString(), serial);
                }
            }
        }

        // LauncherApps intentionally hides packages disabled by IceBox. Query PackageManager with
        // disabled components included so current-user frozen apps remain discoverable.
        android.os.UserHandle currentProfile = Process.myUserHandle();
        long currentSerial = manager.getSerialNumberForUser(currentProfile);
        UserHandle currentUser = new UserHandle(currentSerial, currentProfile);
        PackageManager pm = ctx.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS;
        List<ResolveInfo> disabledCandidates = pm.queryIntentActivities(launcherIntent, flags);
        for (ResolveInfo resolveInfo : disabledCandidates) {
            if (isCancelled()) break;
            ActivityInfo activity = resolveInfo.activityInfo;
            if (activity == null || activity.applicationInfo == null) continue;
            String candidateKey = key(currentSerial, activity.packageName, activity.name);
            if (seen.contains(candidateKey)) continue;
            CharSequence label = resolveInfo.loadLabel(pm);
            if (label == null || label.length() == 0) label = activity.applicationInfo.loadLabel(pm);
            boolean disabled = !activity.enabled || !activity.applicationInfo.enabled
                    || PackageManagerUtils.isAppSuspended(activity.applicationInfo);
            AppPojo app = createPojo(currentUser, activity.packageName, activity.name,
                    label == null ? activity.packageName : label, disabled,
                    excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList);
            apps.add(app);
            seen.add(candidateKey);
            SmartStateStore.rememberApp(ctx, activity.packageName, activity.name, app.getName(), currentSerial);
        }

        // Persistent catalog is the final safety net: a frozen package can disappear from both
        // LauncherApps and intent queries, but it is still installed and must stay searchable.
        for (AppCatalogRecord remembered : SmartStateStore.getRememberedApps(ctx, currentSerial)) {
            String candidateKey = key(currentSerial, remembered.packageName, remembered.activityName);
            if (seen.contains(candidateKey)) continue;
            try {
                ApplicationInfo info = pm.getApplicationInfo(remembered.packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
                boolean disabled = !info.enabled || PackageManagerUtils.isAppSuspended(info);
                AppPojo app = createPojo(currentUser, remembered.packageName, remembered.activityName,
                        remembered.label, disabled,
                        excludedAppList, excludedFromHistoryAppList, excludedShortcutsAppList);
                // If a launcher activity vanished from all normal queries, treat it as frozen until
                // a live launch/reconciliation proves otherwise.
                app.setDisabled(true);
                apps.add(app);
                seen.add(candidateKey);
            } catch (PackageManager.NameNotFoundException e) {
                SmartStateStore.forgetPackage(ctx, remembered.packageName);
            }
        }

        Map<String, AppRecord> customApps = DBHelper.getCustomAppData(ctx);
        for (AppPojo app : apps) {
            AppRecord customApp = customApps.get(app.getComponentName());
            if (customApp != null && customApp.hasCustomName()) app.setName(customApp.name);
        }

        Log.i(TAG, (System.currentTimeMillis() - start) + " milliseconds to list apps including frozen catalog");
        return apps;
    }

    private String key(long serial, String packageName, String activityName) {
        return serial + "|" + packageName + "/" + activityName;
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
