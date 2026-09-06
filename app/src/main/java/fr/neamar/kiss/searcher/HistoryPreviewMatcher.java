package fr.neamar.kiss.searcher;

import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.PojoWithTags;

/** Lightweight matcher for the history-first search stage. */
final class HistoryPreviewMatcher {
    private HistoryPreviewMatcher() {
    }

    static List<Pojo> match(MainActivity activity, SharedPreferences prefs, String query,
                            List<Pojo> historySeed) {
        List<Pojo> matches = new ArrayList<>();
        String normalizedQuery = normalize(query);
        if (activity == null || prefs == null || normalizedQuery.isEmpty()
                || historySeed == null || historySeed.isEmpty()) {
            return matches;
        }

        Set<String> excludedFavoriteIds = KissApplication.getApplication(activity)
                .getDataHandler().getExcludedFavorites();
        boolean detectFrozen = prefs.getBoolean("smart-detect-frozen-apps", true);
        boolean keepFrozenSearchable = prefs.getBoolean("smart-keep-frozen-searchable", true);
        boolean enableExcludedApps = prefs.getBoolean("enable-excluded-apps", false);

        for (Pojo pojo : historySeed) {
            if (Thread.currentThread().isInterrupted()) return matches;
            if (pojo == null || excludedFavoriteIds.contains(pojo.getFavoriteId())) continue;
            if (pojo instanceof AppPojo) {
                AppPojo app = (AppPojo) pojo;
                if (app.isExcluded() && !enableExcludedApps) continue;
                if (app.isDisabled() && (!detectFrozen || !keepFrozenSearchable)) continue;
            }

            String name = normalize(pojo.getName());
            String candidate = candidateText(pojo);
            boolean obvious = name.startsWith(normalizedQuery)
                    || containsTokenPrefix(name, normalizedQuery);
            if (!obvious && normalizedQuery.length() >= 2) {
                obvious = candidate.contains(normalizedQuery);
            }
            if (obvious) matches.add(pojo);
        }
        // Keep the instant history-first preview in the exact same bottom-anchored order as the
        // completed QuerySearcher. Java's List.sort is stable, so equal matches preserve the seed's
        // oldest-to-newest order and therefore keep the more recent equal match nearer the bottom.
        matches.sort((left, right) -> {
            int group = Integer.compare(QueryResultComparator.priorityGroup(left),
                    QueryResultComparator.priorityGroup(right));
            if (group != 0) return group;

            int quality = Integer.compare(matchQuality(left, normalizedQuery),
                    matchQuality(right, normalizedQuery));
            if (quality != 0) return quality;
            return 0;
        });
        return matches;
    }

    private static int matchQuality(Pojo pojo, String normalizedQuery) {
        if (pojo == null || normalizedQuery.isEmpty()) return 0;
        String name = normalize(pojo.getName());
        String candidate = candidateText(pojo);
        if (name.equals(normalizedQuery)) return 5;
        if (name.startsWith(normalizedQuery)) return 4;
        if (containsTokenPrefix(name, normalizedQuery)) return 3;
        if (name.contains(normalizedQuery)) return 2;
        if (candidate.contains(normalizedQuery)) return 1;
        return 0;
    }

    private static String candidateText(Pojo pojo) {
        StringBuilder text = new StringBuilder();
        if (!TextUtils.isEmpty(pojo.getName())) text.append(pojo.getName()).append(' ');
        if (pojo instanceof PojoWithTags) {
            String tags = ((PojoWithTags) pojo).getTags();
            if (!TextUtils.isEmpty(tags)) text.append(tags).append(' ');
        }
        if (pojo instanceof AppPojo) {
            String packageName = ((AppPojo) pojo).packageName;
            if (!TextUtils.isEmpty(packageName)) text.append(packageName);
        }
        return normalize(text.toString());
    }

    private static boolean containsTokenPrefix(String name, String query) {
        if (name.isEmpty() || query.isEmpty() || query.indexOf(' ') >= 0) return false;
        for (String token : name.split("\\s+")) {
            if (token.startsWith(query)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }
}
