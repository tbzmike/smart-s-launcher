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
import java.util.Map;

/**
 * Cached snapshot of foreground app usage since local midnight.
 *
 * PACKAGE_USAGE_STATS is an AppOps-gated special permission. The manifest declaration alone does
 * not grant access, so callers can distinguish "no usage" from "usage access unavailable".
 */
public final class AppUsageTodayStore {
    private static final long CACHE_MAX_AGE_MS = 30_000L;

    private static Snapshot cachedSnapshot;
    private static long cachedAtElapsed;
    private static long cachedDayStart;

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
    public static synchronized Snapshot getToday(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        long start = startOfToday();
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        if (cachedSnapshot != null
                && cachedDayStart == start
                && nowElapsed - cachedAtElapsed < CACHE_MAX_AGE_MS) {
            return cachedSnapshot;
        }

        Snapshot fresh = queryToday(appContext, start);
        cachedSnapshot = fresh;
        cachedDayStart = start;
        cachedAtElapsed = nowElapsed;
        return fresh;
    }

    @NonNull
    private static Snapshot queryToday(@NonNull Context context, long start) {
        if (!hasUsageAccess(context)) {
            return new Snapshot(false, Collections.emptyMap());
        }

        UsageStatsManager manager = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return new Snapshot(false, Collections.emptyMap());
        }

        Map<String, UsageStats> stats;
        try {
            stats = manager.queryAndAggregateUsageStats(start, System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            return new Snapshot(false, Collections.emptyMap());
        }
        if (stats == null) {
            return new Snapshot(false, Collections.emptyMap());
        }

        Map<String, Long> result = new HashMap<>(Math.max(16, stats.size() * 2));
        for (Map.Entry<String, UsageStats> entry : stats.entrySet()) {
            UsageStats usage = entry.getValue();
            String packageName = entry.getKey();
            if (usage == null || packageName == null) continue;
            long duration = Math.max(0L, usage.getTotalTimeInForeground());
            if (duration > 0L) result.put(packageName, duration);
        }
        return new Snapshot(true, Collections.unmodifiableMap(result));
    }

    private static long startOfToday() {
        Calendar midnight = Calendar.getInstance();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        return midnight.getTimeInMillis();
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
