package fr.neamar.kiss.utils;

import androidx.annotation.Nullable;

import fr.neamar.kiss.pojo.Pojo;

/**
 * Process-local fallback for the exact result the user most recently selected.
 *
 * The history database stores stable ids, but some result families (dynamic shortcuts and indexed
 * communication rows in particular) can temporarily disappear from their provider between the tap
 * and the next Home render. Keeping the already-verified Pojo lets history render that same object
 * immediately instead of dropping the click. Persistent providers/DB remain the primary source.
 */
public final class RecentLaunchTracker {
    private static volatile Pojo mostRecent;

    private RecentLaunchTracker() { }

    public static void remember(@Nullable Pojo pojo) {
        if (pojo != null) mostRecent = pojo;
    }

    @Nullable
    public static Pojo resolve(String id) {
        Pojo candidate = mostRecent;
        if (candidate == null || id == null || !id.equals(candidate.getHistoryId())) return null;
        return candidate;
    }

    public static void clearIfMatches(String id) {
        Pojo candidate = mostRecent;
        if (candidate != null && id != null && id.equals(candidate.getHistoryId())) {
            mostRecent = null;
        }
    }
}
