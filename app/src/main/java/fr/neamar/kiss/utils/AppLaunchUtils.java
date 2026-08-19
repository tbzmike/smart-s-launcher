package fr.neamar.kiss.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;

import java.util.List;

import fr.neamar.kiss.KissApplication;

public final class AppLaunchUtils {
    private static final int ANDROID_UID_USER_RANGE = 100000;

    private AppLaunchUtils() {}

    public static boolean launchPackage(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        boolean disabled = false;
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            disabled = !appInfo.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }

        ResolveInfo resolveInfo = resolveLauncher(pm, packageName);
        if (resolveInfo != null && resolveInfo.activityInfo != null) {
            ActivityInfo activity = resolveInfo.activityInfo;
            disabled |= !activity.enabled;
        }

        if (disabled) {
            if (!KissApplication.getApplication(context).getRootHandler().isRootActivated()
                    || !KissApplication.getApplication(context).getRootHandler().isRootAvailable()) return false;
            int userId = Process.myUid() / ANDROID_UID_USER_RANGE;
            if (!KissApplication.getApplication(context).getRootHandler().enableApp(packageName, userId)) return false;
            if (resolveInfo != null && resolveInfo.activityInfo != null) {
                KissApplication.getApplication(context).getRootHandler().enableComponent(
                        packageName, resolveInfo.activityInfo.name, userId);
            }
            resolveInfo = resolveLauncher(pm, packageName);
        }

        Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
        if (launchIntent == null && resolveInfo != null && resolveInfo.activityInfo != null) {
            launchIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(packageName, resolveInfo.activityInfo.name));
        }
        if (launchIntent == null) return false;
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launchIntent);
            KissApplication.getApplication(context).getDataHandler().reloadApps();
            return true;
        } catch (RuntimeException e) {
            Log.w("AppLaunchUtils", "Unable to launch package " + packageName, e);
            return false;
        }
    }

    private static ResolveInfo resolveLauncher(PackageManager pm, String packageName) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName);
        List<ResolveInfo> results = pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS);
        return results.isEmpty() ? null : results.get(0);
    }
}
