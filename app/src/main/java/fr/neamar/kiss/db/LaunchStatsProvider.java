package fr.neamar.kiss.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;

import androidx.annotation.NonNull;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.utils.AppIdentityResolver;

/**
 * Reads launch metadata in one grouped query so list rendering never performs a database query
 * per row. The caller should run this method off the main thread.
 */
public final class LaunchStatsProvider {
    private LaunchStatsProvider() {
    }

    public static final class LaunchStats {
        public final long lastLaunchTime;
        public final int launchesToday;
        public final int launchesLast24Hours;
        public final int launchesLast7Days;
        public final int totalLaunches;

        LaunchStats(long lastLaunchTime, int launchesToday, int launchesLast24Hours,
                    int launchesLast7Days, int totalLaunches) {
            this.lastLaunchTime = lastLaunchTime;
            this.launchesToday = launchesToday;
            this.launchesLast24Hours = launchesLast24Hours;
            this.launchesLast7Days = launchesLast7Days;
            this.totalLaunches = totalLaunches;
        }
    }

    @NonNull
    public static Map<String, LaunchStats> loadAll(@NonNull Context context) {
        return loadAll(context, null);
    }

    @NonNull
    public static Map<String, LaunchStats> loadAll(@NonNull Context context,
                                                   CancellationSignal cancellationSignal) {
        return DatabaseRecovery.run(context, recoveryDb -> {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        long startOfToday = start.getTimeInMillis();
        long now = System.currentTimeMillis();
        long startOf24Hours = Math.max(0L, now - 24L * 60L * 60L * 1000L);
        long startOf7Days = Math.max(0L, now - 7L * 24L * 60L * 60L * 1000L);

        HashMap<String, LaunchStats> stats = new HashMap<>();
        SQLiteDatabase db = recoveryDb;
        String sql = "SELECT record, MAX(timeStamp), "
                + "SUM(CASE WHEN timeStamp >= ? THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN timeStamp >= ? THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN timeStamp >= ? THEN 1 ELSE 0 END), "
                + "COUNT(*) FROM history GROUP BY record";
        String[] args = new String[]{
                Long.toString(startOfToday),
                Long.toString(startOf24Hours),
                Long.toString(startOf7Days)
        };
        try (Cursor cursor = cancellationSignal == null
                ? db.rawQuery(sql, args)
                : db.rawQuery(sql, args, cancellationSignal)) {
            while (cursor.moveToNext()) {
                stats.put(cursor.getString(0), new LaunchStats(
                        cursor.getLong(1), cursor.getInt(2), cursor.getInt(3),
                        cursor.getInt(4), cursor.getInt(5)));
            }
        }
        mergeCanonicalAppAliases(context, stats);
        return stats;

        });
    }

    /**
     * Merge legacy history rows for wrapper shortcuts into the real app's launch statistics.
     *
     * New launches are canonicalized before insertion, but older databases can contain both
     * app://Facebook and shortcut://IceBox/Facebook rows. Every consumer of LaunchStatsProvider
     * receives the same merged values, so timestamps and launch counts agree across list, cards,
     * metadata and other launcher surfaces.
     */
    private static void mergeCanonicalAppAliases(@NonNull Context context,
                                                  @NonNull Map<String, LaunchStats> stats) {
        if (stats.isEmpty()) return;

        final DataHandler dataHandler;
        try {
            dataHandler = KissApplication.getApplication(context).getDataHandler();
        } catch (RuntimeException e) {
            return;
        }
        if (dataHandler == null) return;

        // Build direct history-id -> package mappings once. The previous implementation called
        // DataHandler.getPojo() for every grouped history row; Provider.findById() is a linear scan,
        // so a large history multiplied app/shortcut scans and made Pixel HOME resume extremely
        // expensive. This keeps the same merged semantics with O(apps + shortcuts + historyRows).
        HashMap<String, String> packageByHistoryId = new HashMap<>();
        List<AppPojo> apps = dataHandler.getApplications();
        if (apps != null) {
            for (AppPojo app : apps) {
                if (app == null || app.packageName == null) continue;
                packageByHistoryId.put(app.getHistoryId(), app.packageName);
            }
        }

        List<ShortcutPojo> shortcuts = dataHandler.getPinnedShortcuts();
        if (shortcuts != null) {
            for (ShortcutPojo shortcut : shortcuts) {
                if (shortcut == null
                        || !AppIdentityResolver.isAppAliasShortcut(
                        context, dataHandler, shortcut)) continue;
                String packageName = AppIdentityResolver.canonicalPackage(
                        context, dataHandler, shortcut);
                if (packageName != null && !packageName.isEmpty()) {
                    packageByHistoryId.put(shortcut.getHistoryId(), packageName);
                }
            }
        }

        if (packageByHistoryId.isEmpty()) return;

        HashMap<String, MutableStats> totalsByPackage = new HashMap<>();
        for (Map.Entry<String, LaunchStats> entry : stats.entrySet()) {
            String packageName = packageByHistoryId.get(entry.getKey());
            if (packageName == null) continue;
            totalsByPackage.computeIfAbsent(packageName, ignored -> new MutableStats())
                    .add(entry.getValue());
        }
        if (totalsByPackage.isEmpty()) return;

        HashMap<String, LaunchStats> mergedByPackage = new HashMap<>();
        for (Map.Entry<String, MutableStats> entry : totalsByPackage.entrySet()) {
            MutableStats value = entry.getValue();
            mergedByPackage.put(entry.getKey(),
                    new LaunchStats(value.lastLaunchTime, value.launchesToday,
                            value.launchesLast24Hours, value.launchesLast7Days,
                            value.totalLaunches));
        }

        for (Map.Entry<String, String> entry : packageByHistoryId.entrySet()) {
            LaunchStats merged = mergedByPackage.get(entry.getValue());
            if (merged != null) stats.put(entry.getKey(), merged);
        }
    }

    private static final class MutableStats {
        long lastLaunchTime;
        int launchesToday;
        int launchesLast24Hours;
        int launchesLast7Days;
        int totalLaunches;

        void add(LaunchStats stats) {
            if (stats == null) return;
            lastLaunchTime = Math.max(lastLaunchTime, stats.lastLaunchTime);
            launchesToday = saturatingAdd(launchesToday, stats.launchesToday);
            launchesLast24Hours = saturatingAdd(
                    launchesLast24Hours, stats.launchesLast24Hours);
            launchesLast7Days = saturatingAdd(
                    launchesLast7Days, stats.launchesLast7Days);
            totalLaunches = saturatingAdd(totalLaunches, stats.totalLaunches);
        }

        private int saturatingAdd(int left, int right) {
            long value = Math.max(0L, left) + Math.max(0L, right);
            return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        }
    }
}
