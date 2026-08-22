package fr.neamar.kiss.battery;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class BatteryUsageAnalyzer {
    private BatteryUsageAnalyzer() {}

    public static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    public static Intent usageAccessIntent() {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }

    public static String topForegroundApps24h(Context context, int limit) {
        if (!hasUsageAccess(context)) {
            return "Usage access is off. Enable it so Smart S can correlate abnormal drain with app activity.";
        }
        List<AppDrainCandidate> candidates = candidates(context, 86_400_000L);
        if (candidates.isEmpty()) return "No app activity recorded yet.";

        long totalWeight = 0L;
        for (AppDrainCandidate candidate : candidates) totalWeight += candidate.weightMs;
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (AppDrainCandidate candidate : candidates) {
            if (shown >= Math.max(1, limit)) break;
            if (out.length() > 0) out.append('\n');
            double share = totalWeight <= 0 ? 0.0 : candidate.weightMs * 100.0 / totalWeight;
            out.append(candidate.label).append(" · ")
                    .append(formatDuration(candidate.foregroundMs));
            if (candidate.foregroundServiceMs > 0) {
                out.append(" foreground service ")
                        .append(formatDuration(candidate.foregroundServiceMs));
            }
            out.append(" · activity-correlated share ")
                    .append(String.format(Locale.US, "%.1f%%", share));
            shown++;
        }
        out.append("\n\nSmart S monitors every app Android Usage Access reports and ranks likely contributors when drain rises. The ranking is correlation-based; Android does not give ordinary apps exact per-app battery current/wakelock attribution.");
        return out.toString();
    }

    public static String likelyDrainCause(Context context, long windowMs) {
        if (!hasUsageAccess(context)) return "Usage Access is off";
        List<AppDrainCandidate> candidates = candidates(context, Math.max(5 * 60_000L, windowMs));
        if (candidates.isEmpty()) return "No active app identified";
        AppDrainCandidate top = candidates.get(0);
        StringBuilder result = new StringBuilder();
        result.append(top.label).append(" (active ").append(formatDuration(top.foregroundMs));
        if (top.foregroundServiceMs > 0) {
            result.append(", foreground service ").append(formatDuration(top.foregroundServiceMs));
        }
        result.append(')');
        return result.toString();
    }

    private static List<AppDrainCandidate> candidates(Context context, long windowMs) {
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return new ArrayList<>();
        long end = System.currentTimeMillis();
        long start = end - Math.max(60_000L, windowMs);
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end);
        if (stats == null || stats.isEmpty()) return new ArrayList<>();

        PackageManager pm = context.getPackageManager();
        List<AppDrainCandidate> result = new ArrayList<>();
        for (UsageStats s : stats) {
            if (s == null || s.getPackageName() == null) continue;
            long foreground = Math.max(0L, s.getTotalTimeInForeground());
            long foregroundService = 0L;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                foregroundService = Math.max(0L, s.getTotalTimeForegroundServiceUsed());
            }
            if (foreground <= 0L && foregroundService <= 0L) continue;
            String label = appLabel(pm, s.getPackageName());
            // Give foreground-service time extra weight because an app can keep doing real work
            // after its activity leaves the screen. This is still a correlation score, not a claim
            // that Android exposes exact per-app current draw.
            long weight = foreground + Math.round(foregroundService * 1.35);
            result.add(new AppDrainCandidate(s.getPackageName(), label, foreground,
                    foregroundService, Math.max(1L, weight)));
        }
        result.sort(Comparator.comparingLong((AppDrainCandidate c) -> c.weightMs).reversed());
        return result;
    }

    private static String appLabel(PackageManager pm, String packageName) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence appLabel = pm.getApplicationLabel(info);
            return appLabel == null ? packageName : appLabel.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }

    private static String formatDuration(long millis) {
        long minutes = Math.max(0L, millis / 60_000L);
        if (minutes < 1L) return "<1m";
        if (minutes < 60L) return minutes + "m";
        return String.format(Locale.US, "%dh %02dm", minutes / 60L, minutes % 60L);
    }

    private static final class AppDrainCandidate {
        final String packageName;
        final String label;
        final long foregroundMs;
        final long foregroundServiceMs;
        final long weightMs;

        AppDrainCandidate(String packageName, String label, long foregroundMs,
                          long foregroundServiceMs, long weightMs) {
            this.packageName = packageName;
            this.label = label;
            this.foregroundMs = foregroundMs;
            this.foregroundServiceMs = foregroundServiceMs;
            this.weightMs = weightMs;
        }
    }
}
