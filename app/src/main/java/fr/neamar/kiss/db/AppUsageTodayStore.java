package fr.neamar.kiss.db;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;

import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-shot snapshot of foreground app usage since local midnight.
 *
 * PACKAGE_USAGE_STATS is an AppOps-gated special permission. The manifest declaration alone does
 * not grant access, so callers can distinguish "no usage" from "usage access unavailable".
 */
public final class AppUsageTodayStore {
    private AppUsageTodayStore() {}

    public static final class Snapshot {
        public final boolean available;
        @NonNull public final Map<String, Long> foregroundMsByPackage;

        Snapshot(boolean available, @NonNull Map<String, Long> foregroundMsByPackage) {
            this.available = available;
            this.foregroundMsByPackage = foregroundMsByPackage;
        }
    }

    @NonNull
    public static Snapshot getToday(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        if (!hasUsageAccess(appContext)) {
            return new Snapshot(false, Collections.emptyMap());
        }

        UsageStatsManager manager = (UsageStatsManager)
                appContext.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return new Snapshot(false, Collections.emptyMap());
        }

        Calendar midnight = Calendar.getInstance();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        long start = midnight.getTimeInMillis();
        long end = System.currentTimeMillis();

        List<UsageStats> stats;
        try {
            stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end);
        } catch (RuntimeException ignored) {
            return new Snapshot(false, Collections.emptyMap());
        }
        if (stats == null) {
            return new Snapshot(false, Collections.emptyMap());
        }

        Map<String, Long> result = new HashMap<>();
        for (UsageStats usage : stats) {
            if (usage == null || usage.getPackageName() == null) continue;
            long duration = Math.max(0L, usage.getTotalTimeInForeground());
            if (duration <= 0L) continue;
            Long previous = result.get(usage.getPackageName());
            result.put(usage.getPackageName(), (previous == null ? 0L : previous) + duration);
        }
        return new Snapshot(true, result);
    }

    private static boolean hasUsageAccess(@NonNull Context context) {
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
