package fr.neamar.kiss.appusage;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Copies the usage history Android still exposes into Smart S's 365-day local timeline. */
public final class AppUsageSync {
    private static final String META_LAST_EVENT_SYNC = "last_event_sync";
    private static final long OVERLAP_MS = 24L * 60L * 60L * 1000L;

    private AppUsageSync() {}

    public static void sync(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        AppUsageStore store = AppUsageStore.get(appContext);
        long now = System.currentTimeMillis();

        importInstalledPackages(appContext, store, now);
        if (AppUsageTracker.hasUsageAccess(appContext)) {
            importUsageEvents(appContext, store, now);
            importDailyUsage(appContext, store, now);
        }
        store.prune(now);
    }

    public static void recordPackageChange(@NonNull Context context, @NonNull String action,
                                           @NonNull String packageName, boolean replacing) {
        if (!AppUsageTracker.isEnabled(context)) return;
        AppUsageStore store = AppUsageStore.get(context);
        long now = System.currentTimeMillis();
        if (android.content.Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            if (replacing) return;
            AppUsageStore.PackageState old = store.getPackageState(packageName);
            String label = old == null ? packageName : old.appLabel;
            boolean system = old != null && old.systemApp;
            String source = old == null ? null : old.source;
            String sourceUri = old == null ? null : old.sourceUri;
            store.putTimeline(new AppUsageStore.TimelineEntry(
                    "uninstall:" + packageName + ":" + now,
                    now, 0L, AppUsageStore.KIND_UNINSTALLED, packageName, label, 0L,
                    system, "Package removed", source, sourceUri));
            store.prune(now);
            return;
        }

        if (android.content.Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            captureCurrentPackage(context, store, packageName,
                    replacing ? AppUsageStore.KIND_UPDATED : AppUsageStore.KIND_INSTALLED, now);
        } else if (android.content.Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            captureCurrentPackage(context, store, packageName, AppUsageStore.KIND_UPDATED, now);
        }
        store.prune(now);
    }

    private static void importInstalledPackages(Context context, AppUsageStore store, long now) {
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packages;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L));
            } else {
                //noinspection deprecation
                packages = pm.getInstalledPackages(0);
            }
        } catch (RuntimeException e) {
            return;
        }

        long cutoff = now - AppUsageStore.RETENTION_MS;
        for (PackageInfo info : packages) {
            if (info == null || TextUtils.isEmpty(info.packageName)) continue;
            PackageMeta meta = packageMeta(pm, info.packageName, info);
            store.putPackageState(meta.toState());

            if (info.firstInstallTime >= cutoff && info.firstInstallTime <= now) {
                store.putTimeline(new AppUsageStore.TimelineEntry(
                        "install:" + info.packageName + ":" + info.firstInstallTime,
                        info.firstInstallTime, 0L, AppUsageStore.KIND_INSTALLED,
                        info.packageName, meta.label, 0L, meta.system,
                        "Installed package discovered from Android package history",
                        meta.source, meta.sourceUri));
            }
            if (info.lastUpdateTime > info.firstInstallTime + 60_000L
                    && info.lastUpdateTime >= cutoff && info.lastUpdateTime <= now) {
                store.putTimeline(new AppUsageStore.TimelineEntry(
                        "update:" + info.packageName + ":" + info.lastUpdateTime,
                        info.lastUpdateTime, 0L, AppUsageStore.KIND_UPDATED,
                        info.packageName, meta.label, 0L, meta.system,
                        "Package updated", meta.source, meta.sourceUri));
            }
        }
    }

    private static void captureCurrentPackage(Context context, AppUsageStore store,
                                              String packageName, String kind, long eventTime) {
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L));
            } else {
                //noinspection deprecation
                info = pm.getPackageInfo(packageName, 0);
            }
            PackageMeta meta = packageMeta(pm, packageName, info);
            store.putPackageState(meta.toState());
            String prefix = AppUsageStore.KIND_UPDATED.equals(kind) ? "update-live:" : "install-live:";
            store.putTimeline(new AppUsageStore.TimelineEntry(
                    prefix + packageName + ":" + eventTime,
                    eventTime, 0L, kind, packageName, meta.label, 0L, meta.system,
                    AppUsageStore.KIND_UPDATED.equals(kind) ? "Package updated" : "Package installed",
                    meta.source, meta.sourceUri));
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // Package broadcasts can race package manager visibility; the next scheduled sync repairs it.
        }
    }

    private static void importUsageEvents(Context context, AppUsageStore store, long now) {
        UsageStatsManager manager = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return;

        long firstAllowed = now - AppUsageStore.RETENTION_MS;
        long lastSync = store.getMeta(META_LAST_EVENT_SYNC, 0L);
        long begin = lastSync <= 0L ? firstAllowed : Math.max(firstAllowed, lastSync - OVERLAP_MS);

        UsageEvents events;
        try {
            events = manager.queryEvents(begin, now);
        } catch (RuntimeException e) {
            return;
        }
        if (events == null) return;

        PackageManager pm = context.getPackageManager();
        Map<String, Long> foregroundStarts = new HashMap<>();
        int screenState = 0; // 1 interactive, -1 non-interactive, 0 unknown
        long screenStateStart = 0L;
        UsageEvents.Event event = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            long time = event.getTimeStamp();
            if (time < firstAllowed || time > now) continue;
            int type = event.getEventType();
            String pkg = event.getPackageName();

            // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED share value 1 across supported Android versions.
            if (type == 1 && !TextUtils.isEmpty(pkg)) {
                foregroundStarts.put(pkg, time);
                continue;
            }
            // MOVE_TO_BACKGROUND / ACTIVITY_PAUSED share value 2.
            if (type == 2 && !TextUtils.isEmpty(pkg)) {
                Long start = foregroundStarts.remove(pkg);
                if (start != null && time >= start) {
                    PackageMeta meta = packageMeta(pm, pkg, null);
                    store.putTimeline(new AppUsageStore.TimelineEntry(
                            "use:" + pkg + ":" + start,
                            start, time, AppUsageStore.KIND_APP_USAGE, pkg, meta.label,
                            time - start, meta.system, "Foreground app session", null, null));
                }
                continue;
            }

            if (type == UsageEvents.Event.SCREEN_INTERACTIVE) {
                if (screenState == -1 && screenStateStart > 0L && time >= screenStateStart) {
                    store.putTimeline(screenEntry(AppUsageStore.KIND_SCREEN_OFF,
                            screenStateStart, time));
                }
                screenState = 1;
                screenStateStart = time;
                continue;
            }
            if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                if (screenState == 1 && screenStateStart > 0L && time >= screenStateStart) {
                    store.putTimeline(screenEntry(AppUsageStore.KIND_SCREEN_ON,
                            screenStateStart, time));
                }
                screenState = -1;
                screenStateStart = time;
                continue;
            }
            if (type == UsageEvents.Event.KEYGUARD_SHOWN) {
                store.putTimeline(pointEntry("locked:" + time, time,
                        AppUsageStore.KIND_LOCKED, "Phone locked"));
                continue;
            }
            if (type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                store.putTimeline(pointEntry("unlocked:" + time, time,
                        AppUsageStore.KIND_UNLOCKED, "Phone unlocked"));
                continue;
            }
            if (type == UsageEvents.Event.USER_INTERACTION && !TextUtils.isEmpty(pkg)) {
                // Cap noisy interaction events to one row per app/minute.
                long minute = time / 60_000L;
                PackageMeta meta = packageMeta(pm, pkg, null);
                store.putTimeline(new AppUsageStore.TimelineEntry(
                        "interaction:" + pkg + ":" + minute,
                        time, 0L, AppUsageStore.KIND_APP_INTERACTION, pkg, meta.label,
                        0L, meta.system, "User interaction recorded by Android", null, null));
                continue;
            }
            if (type == UsageEvents.Event.SHORTCUT_INVOCATION && !TextUtils.isEmpty(pkg)) {
                PackageMeta meta = packageMeta(pm, pkg, null);
                store.putTimeline(new AppUsageStore.TimelineEntry(
                        "shortcut:" + pkg + ":" + time,
                        time, 0L, AppUsageStore.KIND_SHORTCUT, pkg, meta.label,
                        0L, meta.system, "App shortcut invoked", null, null));
            }
        }

        store.setMeta(META_LAST_EVENT_SYNC, now);
    }

    private static void importDailyUsage(Context context, AppUsageStore store, long now) {
        UsageStatsManager manager = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return;
        long begin = now - AppUsageStore.RETENTION_MS;
        List<UsageStats> stats;
        try {
            stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, now);
        } catch (RuntimeException e) {
            return;
        }
        if (stats == null) return;

        PackageManager pm = context.getPackageManager();
        Map<String, Long> merged = new HashMap<>();
        Map<String, PackageMeta> metas = new HashMap<>();
        for (UsageStats usage : stats) {
            if (usage == null || TextUtils.isEmpty(usage.getPackageName())) continue;
            long duration = Math.max(0L, usage.getTotalTimeInForeground());
            if (duration <= 0L) continue;
            long stamp = usage.getFirstTimeStamp() > 0L ? usage.getFirstTimeStamp() : usage.getLastTimeUsed();
            long day = AppUsageStore.startOfDay(stamp > 0L ? stamp : now);
            if (day < AppUsageStore.startOfDay(begin)) continue;
            String key = day + "\n" + usage.getPackageName();
            merged.put(key, merged.getOrDefault(key, 0L) + duration);
            metas.computeIfAbsent(usage.getPackageName(), p -> packageMeta(pm, p, null));
        }
        for (Map.Entry<String, Long> entry : merged.entrySet()) {
            int split = entry.getKey().indexOf('\n');
            long day = Long.parseLong(entry.getKey().substring(0, split));
            String pkg = entry.getKey().substring(split + 1);
            PackageMeta meta = metas.get(pkg);
            store.putDailyUsage(day, pkg, meta == null ? pkg : meta.label,
                    entry.getValue(), meta != null && meta.system);
        }
    }

    private static AppUsageStore.TimelineEntry screenEntry(String kind, long start, long end) {
        return new AppUsageStore.TimelineEntry(
                "screen:" + kind + ":" + start,
                start, end, kind, null, null, Math.max(0L, end - start), false,
                AppUsageStore.KIND_SCREEN_ON.equals(kind) ? "Screen interactive" : "Screen off",
                null, null);
    }

    private static AppUsageStore.TimelineEntry pointEntry(String key, long time, String kind,
                                                           String detail) {
        return new AppUsageStore.TimelineEntry(
                key, time, 0L, kind, null, null, 0L, false, detail, null, null);
    }

    private static PackageMeta packageMeta(PackageManager pm, String packageName,
                                           @Nullable PackageInfo knownInfo) {
        String label = packageName;
        boolean system = false;
        long firstInstall = knownInfo == null ? 0L : knownInfo.firstInstallTime;
        long lastUpdate = knownInfo == null ? 0L : knownInfo.lastUpdateTime;
        try {
            ApplicationInfo app = knownInfo != null && knownInfo.applicationInfo != null
                    ? knownInfo.applicationInfo : pm.getApplicationInfo(packageName, 0);
            CharSequence cs = pm.getApplicationLabel(app);
            if (cs != null && cs.length() > 0) label = cs.toString();
            system = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }

        String installer = null;
        String sourceType = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo sourceInfo = pm.getInstallSourceInfo(packageName);
                installer = sourceInfo.getInstallingPackageName();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    sourceType = packageSourceLabel(sourceInfo.getPackageSource());
                }
            } else {
                //noinspection deprecation
                installer = pm.getInstallerPackageName(packageName);
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }

        String installerLabel = installer;
        if (!TextUtils.isEmpty(installer)) {
            try {
                CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(installer, 0));
                if (cs != null && cs.length() > 0) installerLabel = cs.toString();
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }

        String source = null;
        if (!TextUtils.isEmpty(installerLabel) && !TextUtils.isEmpty(sourceType)) {
            source = installerLabel + " · " + sourceType + " · " + installer;
        } else if (!TextUtils.isEmpty(installerLabel)) {
            source = installerLabel + (TextUtils.equals(installerLabel, installer) ? "" : " · " + installer);
        } else if (!TextUtils.isEmpty(sourceType)) {
            source = sourceType;
        }

        String sourceUri = "com.android.vending".equals(installer)
                ? "https://play.google.com/store/apps/details?id=" + packageName : null;
        return new PackageMeta(packageName, label, system, firstInstall, lastUpdate, source, sourceUri);
    }

    private static String packageSourceLabel(int source) {
        switch (source) {
            case PackageInstaller.PACKAGE_SOURCE_STORE:
                return "Store";
            case PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE:
                return "Local file";
            case PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE:
                return "Downloaded file";
            case PackageInstaller.PACKAGE_SOURCE_OTHER:
                return "Other source";
            case PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED:
            default:
                return "Unspecified source";
        }
    }

    private static final class PackageMeta {
        final String packageName;
        final String label;
        final boolean system;
        final long firstInstall;
        final long lastUpdate;
        final String source;
        final String sourceUri;

        PackageMeta(String packageName, String label, boolean system, long firstInstall,
                    long lastUpdate, String source, String sourceUri) {
            this.packageName = packageName;
            this.label = label;
            this.system = system;
            this.firstInstall = firstInstall;
            this.lastUpdate = lastUpdate;
            this.source = source;
            this.sourceUri = sourceUri;
        }

        AppUsageStore.PackageState toState() {
            return new AppUsageStore.PackageState(packageName, label, system, firstInstall,
                    lastUpdate, source, sourceUri);
        }
    }
}
