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
import fr.neamar.kiss.pojo.Pojo;
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
        public final int totalLaunches;

        LaunchStats(long lastLaunchTime, int launchesToday, int totalLaunches) {
            this.lastLaunchTime = lastLaunchTime;
            this.launchesToday = launchesToday;
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

        HashMap<String, LaunchStats> stats = new HashMap<>();
        SQLiteDatabase db = recoveryDb;
        String sql = "SELECT record, MAX(timeStamp), "
                + "SUM(CASE WHEN timeStamp >= ? THEN 1 ELSE 0 END), COUNT(*) "
                + "FROM history GROUP BY record";
        String[] args = new String[]{Long.toString(startOfToday)};
        try (Cursor cursor = cancellationSignal == null
                ? db.rawQuery(sql, args)
                : db.rawQuery(sql, args, cancellationSignal)) {
            while (cursor.moveToNext()) {
                stats.put(cursor.getString(0), new LaunchStats(
                        cursor.getLong(1), cursor.getInt(2), cursor.getInt(3)));
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

        HashMap<String, MutableStats> totalsByPackage = new HashMap<>();
        HashMap<String, String> packageByHistoryId = new HashMap<>();

        // First merge every raw history id we can resolve to a real app identity.
        for (Map.Entry<String, LaunchStats> entry : new HashMap<>(stats).entrySet()) {
            String historyId = entry.getKey();
            Pojo pojo;
            try {
                pojo = dataHandler.getPojo(historyId);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (pojo == null) continue;

            boolean mergeable = pojo instanceof AppPojo
                    || AppIdentityResolver.isAppAliasShortcut(context, dataHandler, pojo);
            if (!mergeable) continue;

            String packageName = AppIdentityResolver.canonicalPackage(
                    context, dataHandler, pojo);
            if (packageName == null || packageName.isEmpty()) continue;

            packageByHistoryId.put(historyId, packageName);
            totalsByPackage.computeIfAbsent(packageName, ignored -> new MutableStats())
                    .add(entry.getValue());
        }

        if (totalsByPackage.isEmpty()) return;

        HashMap<String, LaunchStats> mergedByPackage = new HashMap<>();
        for (Map.Entry<String, MutableStats> entry : totalsByPackage.entrySet()) {
            MutableStats value = entry.getValue();
            mergedByPackage.put(entry.getKey(),
                    new LaunchStats(value.lastLaunchTime, value.launchesToday, value.totalLaunches));
        }

        // Replace all raw members with the merged value.
        for (Map.Entry<String, String> entry : packageByHistoryId.entrySet()) {
            LaunchStats merged = mergedByPackage.get(entry.getValue());
            if (merged != null) stats.put(entry.getKey(), merged);
        }

        // Also expose the merged total through current app/alias ids that may not have their own
        // raw history row yet. This keeps frozen/remembered aliases and the normal app icon in sync.
        List<AppPojo> apps = dataHandler.getApplications();
        if (apps != null) {
            for (AppPojo app : apps) {
                if (app == null) continue;
                LaunchStats merged = mergedByPackage.get(app.packageName);
                if (merged != null) stats.put(app.getHistoryId(), merged);
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
                LaunchStats merged = mergedByPackage.get(packageName);
                if (merged != null) stats.put(shortcut.getHistoryId(), merged);
            }
        }
    }

    private static final class MutableStats {
        long lastLaunchTime;
        int launchesToday;
        int totalLaunches;

        void add(LaunchStats stats) {
            if (stats == null) return;
            lastLaunchTime = Math.max(lastLaunchTime, stats.lastLaunchTime);
            launchesToday = saturatingAdd(launchesToday, stats.launchesToday);
            totalLaunches = saturatingAdd(totalLaunches, stats.totalLaunches);
        }

        private int saturatingAdd(int left, int right) {
            long value = Math.max(0L, left) + Math.max(0L, right);
            return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        }
    }
}
