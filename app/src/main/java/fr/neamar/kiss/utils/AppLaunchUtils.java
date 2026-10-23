package fr.neamar.kiss.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;

public final class AppLaunchUtils {
    private static final int ANDROID_UID_USER_RANGE = 100000;
    private static final long ENABLED_STATE_CACHE_MS = 15000L;
    private static final ConcurrentHashMap<String, EnabledState> ENABLED_STATE_CACHE =
            new ConcurrentHashMap<>();

    // Zero core workers: automatic unfreeze runs only after a user tap, and the
    // worker exits after idling. Root prompts never block Android's UI thread.
    private static final ThreadPoolExecutor ENABLE_EXECUTOR = new ThreadPoolExecutor(
            0, 1, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16),
            runnable -> {
                Thread thread = new Thread(runnable, "smart-s-launch-enable");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppLaunchUtils() {}

    /**
     * Asynchronously confirm current-user package state and enable it only when frozen.
     * Never report success based only on a queued root command.
     */
    public static void ensurePackageEnabledAsync(Context context, String packageName,
                                                 Consumer<Boolean> onComplete) {
        Context app = context.getApplicationContext();
        try {
            ENABLE_EXECUTOR.execute(() -> {
                boolean enabled = false;
                try {
                    enabled = ensurePackageEnabled(app, packageName);
                } catch (RuntimeException e) {
                    Log.w("AppLaunchUtils", "Enable failed for " + packageName, e);
                }
                final boolean result = enabled;
                MAIN.post(() -> onComplete.accept(result));
            });
        } catch (RuntimeException e) {
            Log.w("AppLaunchUtils", "Auto-enable queue unavailable", e);
            MAIN.post(() -> onComplete.accept(false));
        }
    }

    public static boolean isPackageInstalled(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        try {
            context.getPackageManager().getApplicationInfo(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

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

    /** Tap-time package state check bypasses the 15-second display/cache snapshot. */
    public static boolean isPackageEnabledNow(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        boolean enabled = queryPackageEnabled(context, packageName);
        ENABLED_STATE_CACHE.put(packageName,
                new EnabledState(enabled, SystemClock.elapsedRealtime()));
        return enabled;
    }

    private static boolean queryPackageEnabled(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            int state = pm.getApplicationEnabledSetting(packageName);
            return appInfo.enabled
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
            return false;
        }
    }

    public static void invalidatePackageState(String packageName) {
        if (packageName != null) ENABLED_STATE_CACHE.remove(packageName);
    }

    private static void markPackageEnabled(String packageName) {
        if (packageName != null) {
            ENABLED_STATE_CACHE.put(packageName,
                    new EnabledState(true, SystemClock.elapsedRealtime()));
        }
    }

    /**
     * One state refresh after a verified enable. An additional unconditional timed reload
     * doubled the provider/History work and could invalidate an in-flight shortcut launch.
     */
    public static void refreshLauncherAfterEnable(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return;
        markPackageEnabled(packageName);
        Context app = context.getApplicationContext();
        KissApplication.getApplication(app).getDataHandler().reloadApps();
        KissApplication.getApplication(app).getDataHandler().reloadShortcuts();
        app.sendBroadcast(MainActivity.internalBroadcast(app, MainActivity.LOAD_OVER));
    }

    /** Enable a frozen current-user package without launching it. */
    public static boolean ensurePackageEnabled(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        // Frozen/hidden apps can disappear from PackageManager queries. A saved known
        // package identity should be offered to the authorised root enable command;
        // the pm exit status and fresh query decide whether it actually exists.
        if (isPackageEnabledNow(context, packageName)) return true;
        if (!KissApplication.getApplication(context).getRootHandler().isRootActivated()
                || !KissApplication.getApplication(context).getRootHandler().isRootAvailable()) {
            Log.w("AppLaunchUtils", "Auto-enable requires an authorized root session: " + packageName);
            return false;
        }

        PackageManager pm = context.getPackageManager();
        ResolveInfo launcher = resolveLauncher(pm, packageName);
        int userId = Process.myUid() / ANDROID_UID_USER_RANGE;
        if (!KissApplication.getApplication(context).getRootHandler().enableApp(packageName, userId)) return false;
        if (launcher != null && launcher.activityInfo != null) {
            KissApplication.getApplication(context).getRootHandler().enableComponent(
                    packageName, launcher.activityInfo.name, userId);
        }

        // 'su' and 'pm enable' succeeding does not guarantee that PackageManager has
        // published the enabled state. Do not claim success or refresh the provider until
        // fresh, uncached PackageManager state confirms the package became launchable.
        for (int attempt = 0; attempt < 5; attempt++) {
            if (isPackageEnabledNow(context, packageName)) return true;
            if (attempt < 4) SystemClock.sleep(100L);
        }
        Log.w("AppLaunchUtils", "Package remains frozen after enable: " + packageName);
        return false;
    }

    public static boolean launchPackage(Context context, String packageName) {
        boolean frozen = !isPackageEnabledNow(context, packageName);
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
            if (frozen) refreshLauncherAfterEnable(context, packageName);
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
