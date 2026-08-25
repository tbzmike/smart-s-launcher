package fr.neamar.kiss.appusage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Converts Android's activity-level foreground records into app-level usage sessions.
 *
 * Android emits a pause/resume pair while one app moves between its own activities. Displaying
 * every pair creates several rows for what the user experienced as one continuous app session.
 * Adjacent records from the same package are therefore joined only when their transition gap is
 * short and no other visible event occurs between them.
 */
public final class AppUsageTimelineCompactor {
    static final long MAX_ACTIVITY_TRANSITION_GAP_MS = 5_000L;

    private AppUsageTimelineCompactor() {
    }

    public static List<AppUsageStore.TimelineEntry> compact(
            List<AppUsageStore.TimelineEntry> source) {
        List<AppUsageStore.TimelineEntry> ordered = new ArrayList<>(source);
        ordered.sort(Comparator.comparingLong(
                (AppUsageStore.TimelineEntry entry) -> entry.startMs).reversed());

        List<AppUsageStore.TimelineEntry> compacted = new ArrayList<>(ordered.size());
        AppUsageStore.TimelineEntry pendingUsage = null;
        for (AppUsageStore.TimelineEntry entry : ordered) {
            if (pendingUsage != null && canMerge(pendingUsage, entry)) {
                pendingUsage = merge(pendingUsage, entry);
                continue;
            }

            if (pendingUsage != null) {
                compacted.add(pendingUsage);
                pendingUsage = null;
            }

            if (isAppUsage(entry)) pendingUsage = entry;
            else compacted.add(entry);
        }
        if (pendingUsage != null) compacted.add(pendingUsage);
        return compacted;
    }

    private static boolean canMerge(AppUsageStore.TimelineEntry newer,
                                    AppUsageStore.TimelineEntry older) {
        if (!isAppUsage(newer) || !isAppUsage(older)) return false;
        if (newer.packageName == null || newer.packageName.isEmpty()
                || !newer.packageName.equals(older.packageName)) {
            return false;
        }

        long gapMs = newer.startMs - effectiveEnd(older);
        return gapMs <= MAX_ACTIVITY_TRANSITION_GAP_MS;
    }

    private static AppUsageStore.TimelineEntry merge(AppUsageStore.TimelineEntry newer,
                                                      AppUsageStore.TimelineEntry older) {
        long startMs = Math.min(newer.startMs, older.startMs);
        long endMs = Math.max(effectiveEnd(newer), effectiveEnd(older));
        String label = isEmpty(newer.appLabel) ? older.appLabel : newer.appLabel;
        return new AppUsageStore.TimelineEntry(
                "compact-use:" + newer.packageName + ":" + startMs + ":" + endMs,
                startMs,
                endMs,
                AppUsageStore.KIND_APP_USAGE,
                newer.packageName,
                label,
                Math.max(0L, endMs - startMs),
                newer.systemApp || older.systemApp,
                null,
                null,
                null);
    }

    private static boolean isAppUsage(AppUsageStore.TimelineEntry entry) {
        return AppUsageStore.KIND_APP_USAGE.equals(entry.kind);
    }

    private static long effectiveEnd(AppUsageStore.TimelineEntry entry) {
        return Math.max(entry.endMs, entry.startMs + Math.max(0L, entry.durationMs));
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
