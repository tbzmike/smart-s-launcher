package fr.neamar.kiss.forwarder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure selection policy for Pixel-style favorites.
 *
 * Pinned favorites keep their configured order and always win a slot. Ranked app suggestions only
 * fill empty slots. The returned list is capped to 10 regardless of malformed preferences.
 */
final class PixelFavoritesPolicy {
    static final int DEFAULT_MAX_APPS = 5;
    static final int MAX_APPS = 10;

    private PixelFavoritesPolicy() { }

    static final class PredictionSignal {
        final int launchesToday;
        final int launchesLast24Hours;
        final int launchesLast7Days;
        final long foregroundMsToday;
        final long lastLaunchTime;
        final int totalLaunches;

        PredictionSignal(int launchesToday, int launchesLast24Hours, int launchesLast7Days,
                         long foregroundMsToday, long lastLaunchTime, int totalLaunches) {
            this.launchesToday = Math.max(0, launchesToday);
            this.launchesLast24Hours = Math.max(0, launchesLast24Hours);
            this.launchesLast7Days = Math.max(0, launchesLast7Days);
            this.foregroundMsToday = Math.max(0L, foregroundMsToday);
            this.lastLaunchTime = Math.max(0L, lastLaunchTime);
            this.totalLaunches = Math.max(0, totalLaunches);
        }
    }

    /**
     * Pixel-style suggestions should react to what the user is using now, not be permanently
     * dominated by an app's lifetime count. Compare recent frequency first, then today's foreground
     * usage, then recency and finally lifetime frequency as a stable tie-breaker.
     */
    static int comparePrediction(PredictionSignal left, PredictionSignal right) {
        int byToday = Integer.compare(right.launchesToday, left.launchesToday);
        if (byToday != 0) return byToday;
        int by24h = Integer.compare(right.launchesLast24Hours, left.launchesLast24Hours);
        if (by24h != 0) return by24h;
        int by7d = Integer.compare(right.launchesLast7Days, left.launchesLast7Days);
        if (by7d != 0) return by7d;
        int byForeground = Long.compare(right.foregroundMsToday, left.foregroundMsToday);
        if (byForeground != 0) return byForeground;
        int byRecent = Long.compare(right.lastLaunchTime, left.lastLaunchTime);
        if (byRecent != 0) return byRecent;
        return Integer.compare(right.totalLaunches, left.totalLaunches);
    }


    static int clampMaxApps(int value) {
        return Math.max(1, Math.min(MAX_APPS, value));
    }

    static List<String> select(List<String> pinnedIds, List<String> rankedAppIds, int requestedMax) {
        int max = clampMaxApps(requestedMax);
        List<String> result = new ArrayList<>(max);
        Set<String> seen = new HashSet<>(max * 2);

        if (pinnedIds != null) {
            for (String id : pinnedIds) {
                if (id == null || id.isEmpty() || !seen.add(id)) continue;
                result.add(id);
                if (result.size() >= max) return result;
            }
        }

        if (rankedAppIds != null) {
            for (String id : rankedAppIds) {
                if (id == null || id.isEmpty() || !seen.add(id)) continue;
                result.add(id);
                if (result.size() >= max) break;
            }
        }

        return result;
    }
}
