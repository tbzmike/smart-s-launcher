package fr.neamar.kiss.searcher;

import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.PojoWithTags;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.utils.FrozenAppPreferences;
import fr.neamar.kiss.utils.ShortcutUtil;

/** Lightweight matcher for the history-first search stage. */
final class HistoryPreviewMatcher {
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private HistoryPreviewMatcher() {
    }

    /** Prepared once when saved History changes, rather than normalizing 803 labels per key. */
    static final class Snapshot {
        final List<Entry> entries;

        Snapshot(List<Entry> entries) {
            this.entries = Collections.unmodifiableList(entries);
        }
    }

    private static final class Entry {
        final Pojo pojo;
        final String name;
        final String candidate;

        Entry(Pojo pojo) {
            this.pojo = pojo;
            this.name = normalize(pojo.getName());
            this.candidate = candidateText(pojo);
        }
    }

    static Snapshot prepare(List<Pojo> historySeed) {
        List<Entry> entries = new ArrayList<>(historySeed.size());
        for (Pojo pojo : historySeed) {
            if (pojo != null) entries.add(new Entry(pojo));
        }
        return new Snapshot(entries);
    }

    static List<Pojo> match(MainActivity activity, SharedPreferences prefs, String query,
                            Snapshot history, int historyLimit) {
        List<Pojo> matches = new ArrayList<>();
        List<Pojo> launchTargets = new ArrayList<>();
        String normalizedQuery = normalize(query);
        if (activity == null || prefs == null || normalizedQuery.isEmpty()
                || history == null || historyLimit <= 0 || history.entries.isEmpty()) {
            return matches;
        }

        Set<String> excludedFavoriteIds = KissApplication.getApplication(activity)
                .getDataHandler().getExcludedFavorites();
        boolean keepFrozenSearchable = FrozenAppPreferences.keepSearchable(activity);
        boolean enableExcludedApps = prefs.getBoolean("enable-excluded-apps", false);

        // Apply the current History limit BEFORE matching. Older rows must not leak into the
        // instant stage when the user reduces 803 to 403; the Search-results limit is separate.
        int from = Math.max(0, history.entries.size() - historyLimit);
        for (int i = from; i < history.entries.size(); i++) {
            Entry entry = history.entries.get(i);
            Pojo pojo = entry.pojo;
            if (pojo == null || excludedFavoriteIds.contains(pojo.getFavoriteId())) continue;
            if (pojo instanceof AppPojo) {
                AppPojo app = (AppPojo) pojo;
                if (app.isExcluded() && !enableExcludedApps) continue;
                if (app.isDisabled() && !keepFrozenSearchable) continue;
            } else if (pojo instanceof ShortcutPojo) {
                ShortcutPojo shortcut = (ShortcutPojo) pojo;
                if (shortcut.isDisabled()
                        && !ShortcutUtil.isIceBoxPublisher(activity, shortcut.packageName)
                        && !keepFrozenSearchable) continue;
            }

            String name = entry.name;
            String candidate = entry.candidate;
            boolean obvious = name.startsWith(normalizedQuery)
                    || containsTokenPrefix(name, normalizedQuery);
            if (!obvious && normalizedQuery.length() >= 2) {
                obvious = candidate.contains(normalizedQuery);
            }
            if (obvious) {
                if (pojo instanceof AppPojo || pojo instanceof ShortcutPojo) {
                    launchTargets.add(pojo);
                } else {
                    matches.add(pojo);
                }
            }
        }
        // Stable partition: preserve recency inside each group while keeping launchable app and
        // shortcut matches at the physical bottom from the very first history-preview frame.
        matches.addAll(launchTargets);
        return matches;
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
        int start = 0;
        while (start < name.length()) {
            if (name.startsWith(query, start)) return true;
            int next = name.indexOf(' ', start);
            if (next < 0) break;
            start = next + 1;
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return SEPARATORS.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }
}
