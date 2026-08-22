package fr.neamar.kiss.battery;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
        if (!hasUsageAccess(context)) return "Usage access is off. Enable it to correlate heavy drain with foreground app activity.";
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return "Usage statistics unavailable on this device.";
        long end = System.currentTimeMillis();
        long start = end - 86_400_000L;
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end);
        if (stats == null || stats.isEmpty()) return "No foreground app activity recorded yet.";
        List<UsageStats> usable = new ArrayList<>();
        for (UsageStats s : stats) if (s.getTotalTimeInForeground() > 0) usable.add(s);
        usable.sort(Comparator.comparingLong(UsageStats::getTotalTimeInForeground).reversed());
        PackageManager pm = context.getPackageManager();
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (UsageStats s : usable) {
            if (shown >= Math.max(1, limit)) break;
            String label = s.getPackageName();
            try {
                ApplicationInfo info = pm.getApplicationInfo(s.getPackageName(), 0);
                CharSequence appLabel = pm.getApplicationLabel(info);
                if (appLabel != null) label = appLabel.toString();
            } catch (PackageManager.NameNotFoundException ignored) { }
            long minutes = s.getTotalTimeInForeground() / 60_000L;
            if (out.length() > 0) out.append('\n');
            out.append(label).append(" · ")
                    .append(String.format(Locale.US, "%dh %02dm", minutes / 60, minutes % 60));
            shown++;
        }
        if (out.length() == 0) return "No foreground app activity recorded yet.";
        return out.toString();
    }
}
