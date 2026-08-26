package fr.neamar.kiss.utils;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

import fr.neamar.kiss.pojo.Pojo;

/**
 * Process-local fallback for exact results the user recently selected.
 *
 * The persistent history database stores stable ids, but some result families (especially dynamic
 * shortcuts) can temporarily disappear from their provider between launches. Keep a small bounded
 * cache keyed by history id so older recent entries remain reconstructable after another item is
 * launched. Persistent providers/DB remain the primary source and this cache is only a bridge.
 */
public final class RecentLaunchTracker {
    private static final int MAX_RECENT = 64;
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Pojo> recentByHistoryId =
            new LinkedHashMap<>(MAX_RECENT + 1, 0.75f, true);
    private static volatile Pojo mostRecent;

    private RecentLaunchTracker() { }

    public static void remember(@Nullable Pojo pojo) {
        if (pojo == null) return;
        String historyId = pojo.getHistoryId();
        if (historyId == null) return;

        mostRecent = pojo;
        synchronized (LOCK) {
            recentByHistoryId.put(historyId, pojo);
            while (recentByHistoryId.size() > MAX_RECENT) {
                String eldest = recentByHistoryId.keySet().iterator().next();
                recentByHistoryId.remove(eldest);
            }
        }
    }

    @Nullable
    public static Pojo getMostRecent() {
        return mostRecent;
    }

    @Nullable
    public static Pojo resolve(String id) {
        if (id == null) return null;
        synchronized (LOCK) {
            return recentByHistoryId.get(id);
        }
    }

    public static void clearIfMatches(String id) {
        if (id == null) return;
        synchronized (LOCK) {
            recentByHistoryId.remove(id);
        }
        Pojo candidate = mostRecent;
        if (candidate != null && id.equals(candidate.getHistoryId())) {
            mostRecent = null;
        }
    }

    static int cachedCountForTests() {
        synchronized (LOCK) {
            return recentByHistoryId.size();
        }
    }
}
