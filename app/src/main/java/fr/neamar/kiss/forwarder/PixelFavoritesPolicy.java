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
