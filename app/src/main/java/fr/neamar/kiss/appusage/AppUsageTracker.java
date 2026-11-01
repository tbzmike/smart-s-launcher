package fr.neamar.kiss.appusage;

import android.app.AppOpsManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import fr.neamar.kiss.db.AppUsageTodayStore;
import fr.neamar.kiss.ui.UniversalHistoryTimestamp;
import fr.neamar.kiss.utils.IdleExecutors;

/** Opt-in controller for the local app-usage timeline. */
public final class AppUsageTracker {
    public static final String PREF_ENABLED = "enable-app-usage-tracking";
    private static final String PREF_DEFAULT_REPAIRED = "app-usage-default-repaired-v33001";
    private static final int JOB_ID = 0x53535547; // "SSUG"
    private static final long PERIOD_MS = 6L * 60L * 60L * 1000L;
    private static final ThreadPoolExecutor EXECUTOR = IdleExecutors.single("smart-s-app-usage");
    private static Future<?> inFlight;

    private AppUsageTracker() {}

    public static boolean isEnabled(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_ENABLED, true);
    }

    /**
     * Initialize the historical default once without overriding an explicitly saved OFF choice.
     */
    public static void repairPerformanceRegression(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        android.content.SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(appContext);
        if (prefs.getBoolean(PREF_DEFAULT_REPAIRED, false)) return;
        boolean enabled = prefs.getBoolean(PREF_ENABLED, false);
        if (AppUsageEnablementPolicy.shouldRestoreDefault(false, enabled,
                prefs.contains(PREF_ENABLED))) enabled = true;
        prefs.edit().putBoolean(PREF_ENABLED, enabled)
                .putBoolean(PREF_DEFAULT_REPAIRED, true).apply();
        if (enabled) ensureScheduled(appContext);
    }

    public static void setEnabled(@NonNull Context context, boolean enabled) {
        Context appContext = context.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
                .putBoolean(PREF_ENABLED, enabled)
                .putBoolean(PREF_DEFAULT_REPAIRED, true)
                .apply();
        if (enabled) {
            ensureScheduled(appContext);
            syncAsync(appContext);
        } else {
            stopDisabledWork(appContext);
        }
    }

    public static synchronized void stopDisabledWork(@NonNull Context context) {
        if (isEnabled(context)) return;
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(JOB_ID);
        if (inFlight != null) inFlight.cancel(true);
        inFlight = null;
        EXECUTOR.purge();
        AppUsageTodayStore.invalidate();
        UniversalHistoryTimestamp.invalidateStats();
    }

    public static void ensureScheduled(@NonNull Context context) {
        if (!isEnabled(context)) { stopDisabledWork(context); return; }
        JobScheduler scheduler = (JobScheduler)
                context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        JobInfo job = new JobInfo.Builder(
                JOB_ID, new ComponentName(context, AppUsageJobService.class))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build();
        scheduler.schedule(job);
    }

    public static synchronized void syncAsync(@NonNull Context context) {
        if (!isEnabled(context) || (inFlight != null && !inFlight.isDone())) return;
        Context appContext = context.getApplicationContext();
        inFlight = EXECUTOR.submit(() -> {
            if (isEnabled(appContext) && !Thread.currentThread().isInterrupted()) {
                AppUsageSync.sync(appContext);
            }
        });
    }

    public static void syncNow(@NonNull Context context) {
        if (!isEnabled(context)) return;
        AppUsageSync.sync(context.getApplicationContext());
    }

    public static boolean hasUsageAccess(@NonNull Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), 0);
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS, info.uid, context.getPackageName());
            } else {
                mode = appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS, info.uid, context.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return false;
        }
    }
}
