package fr.neamar.kiss.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import fr.neamar.kiss.KissApplication;

public final class AppLaunchUtils {
    private static final int ANDROID_UID_USER_RANGE = 100000;
    private static final long ENABLED_STATE_CACHE_MS = 1800L;
    private static final ConcurrentHashMap<String, EnabledState> ENABLED_STATE_CACHE =
            new ConcurrentHashMap<>();

    private AppLaunchUtils() {}

    public static boolean isPackageEnabled(Context context, String packageName) {
        long now = SystemClock.elapsedRealtime();
        EnabledState cached = ENABLED_STATE_CACHE.get(packageName);
        if (cached != null && now - cached.checkedAt < ENABLED_STATE_CACHE_MS) {
            return cached.enabled;
        }
        boolean enabled = queryPackageEnabled(context, packageName);
        ENABLED_STATE_CACHE.put(packageName, new EnabledState(enabled, now));
        return enabled;
    }

    private static boolean queryPackageEnabled(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            int state = pm.getApplicationEnabledSetting(packageName);
            if (!appInfo.enabled
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return false;
            }
            ResolveInfo launcher = resolveLauncher(pm, packageName);
            return launcher == null || launcher.activityInfo == null || launcher.activityInfo.enabled;
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
            return false;
        }
    }

    public static void invalidatePackageState(String packageName) {
        if (packageName != null) ENABLED_STATE_CACHE.remove(packageName);
    }

    /** Enable a frozen current-user package without launching it. */
    public static boolean ensurePackageEnabled(Context context, String packageName) {
        if (isPackageEnabled(context, packageName)) return true;
        if (!KissApplication.getApplication(context).getRootHandler().isRootActivated()
                || !KissApplication.getApplication(context).getRootHandler().isRootAvailable()) return false;

        PackageManager pm = context.getPackageManager();
        ResolveInfo launcher = resolveLauncher(pm, packageName);
        int userId = Process.myUid() / ANDROID_UID_USER_RANGE;
        if (!KissApplication.getApplication(context).getRootHandler().enableApp(packageName, userId)) return false;
        if (launcher != null && launcher.activityInfo != null) {
            KissApplication.getApplication(context).getRootHandler().enableComponent(
                    packageName, launcher.activityInfo.name, userId);
        }
        invalidatePackageState(packageName);
        KissApplication.getApplication(context).getDataHandler().reloadApps();
        return true;
    }

    public static boolean launchPackage(Context context, String packageName) {
        if (!ensurePackageEnabled(context, packageName)) return false;
        PackageManager pm = context.getPackageManager();
        ResolveInfo resolveInfo = resolveLauncher(pm, packageName);
        Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
        if (launchIntent == null && resolveInfo != null && resolveInfo.activityInfo != null) {
            launchIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(packageName, resolveInfo.activityInfo.name));
        }
        if (launchIntent == null) return false;
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launchIntent);
            // A normal launch does not change the app catalog. Avoid a full provider reload here;
            // frozen apps already reload once in ensurePackageEnabled() when their state changes.
            return true;
        } catch (RuntimeException e) {
            Log.w("AppLaunchUtils", "Unable to launch package " + packageName, e);
            invalidatePackageState(packageName);
            return false;
        }
    }

    private static ResolveInfo resolveLauncher(PackageManager pm, String packageName) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName);
        List<ResolveInfo> results = pm.queryIntentActivities(
                intent, PackageManager.MATCH_DISABLED_COMPONENTS);
        return results.isEmpty() ? null : results.get(0);
    }

    private static final class EnabledState {
        final boolean enabled;
        final long checkedAt;

        EnabledState(boolean enabled, long checkedAt) {
            this.enabled = enabled;
            this.checkedAt = checkedAt;
        }
    }
}
