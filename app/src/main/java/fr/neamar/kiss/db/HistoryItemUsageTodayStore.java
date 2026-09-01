package fr.neamar.kiss.db;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculates today's foreground duration for individual shortcut history items.
 *
 * Parent app totals remain owned by {@link AppUsageTodayStore}. This class deliberately does not
 * change the history schema or Android's app-level usage totals. It correlates an existing Smart S
 * shortcut launch timestamp with the target package's foreground interval, so a feature such as
 * Facebook Reels or YouTube Shorts can have its own duration while Facebook/YouTube still show the
 * complete package total.
 */
public final class HistoryItemUsageTodayStore {
    private static final long LAUNCH_MATCH_WINDOW_MS = 10_000L;
    private static final long INTERNAL_ACTIVITY_GAP_MS = 2_000L;

    private HistoryItemUsageTodayStore() {}

    public static final class Snapshot {
        public final boolean available;
        @NonNull public final Map<String, Long> foregroundMsByHistoryId;

        Snapshot(boolean available, @NonNull Map<String, Long> foregroundMsByHistoryId) {
            this.available = available;
            this.foregroundMsByHistoryId = foregroundMsByHistoryId;
        }
    }

    @NonNull
    public static Snapshot getToday(@NonNull Context context,
                                    @NonNull Map<String, String> targetPackageByHistoryId,
                                    boolean usageAccessAvailable) {
        if (!usageAccessAvailable) {
            return new Snapshot(false, Collections.emptyMap());
        }
        if (targetPackageByHistoryId.isEmpty()) {
            return new Snapshot(true, Collections.emptyMap());
        }

        Context appContext = context.getApplicationContext();
        UsageStatsManager manager = (UsageStatsManager)
                appContext.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return new Snapshot(false, Collections.emptyMap());
        }

        long start = startOfToday();
        long end = System.currentTimeMillis();
        Map<String, List<Launch>> launchesByPackage = loadShortcutLaunches(
                appContext, targetPackageByHistoryId, start);
        if (launchesByPackage.isEmpty()) {
            return new Snapshot(true, Collections.emptyMap());
        }

        HashMap<String, Long> durationByHistoryId = new HashMap<>();
        try {
            for (Map.Entry<String, List<Launch>> entry : launchesByPackage.entrySet()) {
                List<Interval> intervals = loadForegroundIntervals(
                        manager, entry.getKey(), start, end);
                attributeIntervals(entry.getValue(), intervals, durationByHistoryId);
            }
        } catch (RuntimeException ignored) {
            return new Snapshot(false, Collections.emptyMap());
        }
        return new Snapshot(true, Collections.unmodifiableMap(durationByHistoryId));
    }

    @NonNull
    private static Map<String, List<Launch>> loadShortcutLaunches(
            @NonNull Context context,
            @NonNull Map<String, String> targetPackageByHistoryId,
            long start) {
        HashMap<String, List<Launch>> launchesByPackage = new HashMap<>();
        android.database.sqlite.SQLiteDatabase db =
                new DB(context.getApplicationContext()).getReadableDatabase();
        String sql = "SELECT record, timeStamp FROM history "
                + "WHERE timeStamp >= ? ORDER BY timeStamp ASC";
        try (android.database.Cursor cursor = db.rawQuery(
                sql, new String[]{Long.toString(start)})) {
            while (cursor.moveToNext()) {
                String historyId = cursor.getString(0);
                String packageName = targetPackageByHistoryId.get(historyId);
                if (packageName == null || packageName.isEmpty()) continue;
                launchesByPackage.computeIfAbsent(packageName, ignored -> new ArrayList<>())
                        .add(new Launch(historyId, cursor.getLong(1)));
            }
        }
        return launchesByPackage;
    }

    @NonNull
    private static List<Interval> loadForegroundIntervals(@NonNull UsageStatsManager manager,
                                                          @NonNull String packageName,
                                                          long start,
                                                          long end) {
        UsageEvents events = manager.queryEvents(start, end);
        if (events == null) return Collections.emptyList();

        ArrayList<Interval> raw = new ArrayList<>();
        UsageEvents.Event event = new UsageEvents.Event();
        long foregroundStart = -1L;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (!packageName.equals(event.getPackageName())) continue;

            int type = event.getEventType();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (foregroundStart < 0L) foregroundStart = Math.max(start, event.getTimeStamp());
            } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND && foregroundStart >= 0L) {
                long background = Math.min(end, event.getTimeStamp());
                if (background > foregroundStart) raw.add(new Interval(foregroundStart, background));
                foregroundStart = -1L;
            }
        }
        if (foregroundStart >= 0L && end > foregroundStart) {
            raw.add(new Interval(foregroundStart, end));
        }
        if (raw.size() < 2) return raw;

        ArrayList<Interval> merged = new ArrayList<>();
        Interval current = raw.get(0);
        for (int i = 1; i < raw.size(); i++) {
            Interval next = raw.get(i);
            if (next.start - current.end <= INTERNAL_ACTIVITY_GAP_MS) {
                current = new Interval(current.start, Math.max(current.end, next.end));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static void attributeIntervals(@NonNull List<Launch> launches,
                                           @NonNull List<Interval> intervals,
                                           @NonNull Map<String, Long> durationByHistoryId) {
        boolean[] assigned = new boolean[intervals.size()];
        for (Launch launch : launches) {
            int bestIndex = -1;
            long bestDistance = Long.MAX_VALUE;
            for (int i = 0; i < intervals.size(); i++) {
                if (assigned[i]) continue;
                Interval interval = intervals.get(i);
                long distance = Math.abs(interval.start - launch.timeStamp);
                if (distance <= LAUNCH_MATCH_WINDOW_MS && distance < bestDistance) {
                    bestIndex = i;
                    bestDistance = distance;
                }
            }
            if (bestIndex < 0) continue;

            assigned[bestIndex] = true;
            Interval interval = intervals.get(bestIndex);
            long duration = Math.max(0L, interval.end - interval.start);
            if (duration > 0L) {
                durationByHistoryId.merge(launch.historyId, duration, Long::sum);
            }
        }
    }

    private static long startOfToday() {
        Calendar midnight = Calendar.getInstance();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        return midnight.getTimeInMillis();
    }

    private static final class Launch {
        final String historyId;
        final long timeStamp;

        Launch(String historyId, long timeStamp) {
            this.historyId = historyId;
            this.timeStamp = timeStamp;
        }
    }

    private static final class Interval {
        final long start;
        final long end;

        Interval(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }
}
