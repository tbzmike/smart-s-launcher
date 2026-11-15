package fr.neamar.kiss.searcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.utils.FrozenAppPreferences;
import fr.neamar.kiss.utils.ShortcutUtil;

/**
 * Previously found QUERY results, independent of actual launch/notification History.
 *
 * SQLite stores only query -> stable provider ID associations; the warm in-memory index is
 * populated off the UI thread by the existing History worker. No per-keystroke SQLite work,
 * extra service, timer, or permanently running thread is created.
 */
public final class SearchRecallCache {
    private static final Object LOCK = new Object();
    private static volatile Snapshot snapshot = Snapshot.empty();

    private SearchRecallCache() { }

    private static final class Entry {
        final String query;
        final String id;
        final Pojo pojo;
        final long sequence;

        Entry(String query, String id, Pojo pojo, long sequence) {
            this.query = query;
            this.id = id;
            this.pojo = pojo;
            this.sequence = sequence;
        }
    }

    private static final class Snapshot {
        final List<Entry> recent;
        final NavigableMap<String, List<Entry>> byQuery;
        final int loadedLimit;
        final boolean ready;

        Snapshot(List<Entry> recent, NavigableMap<String, List<Entry>> byQuery,
                 int loadedLimit, boolean ready) {
            this.recent = recent;
            this.byQuery = byQuery;
            this.loadedLimit = loadedLimit;
            this.ready = ready;
        }

        static Snapshot empty() {
            return new Snapshot(Collections.emptyList(),
                    Collections.unmodifiableNavigableMap(new TreeMap<>()), 0, false);
        }
    }

    /** The reset-history and provider-reload paths must not keep stale or hidden cached results. */
    public static void invalidate() {
        synchronized (LOCK) {
            snapshot = Snapshot.empty();
        }
    }

    /** Called on the existing History worker, never from a keystroke or the UI thread. */
    public static void warm(Context context, DataHandler handler, int maxHistoryResults) {
        int limit = Math.max(0, maxHistoryResults);
        if (limit == 0 || !handler.isAllProvidersLoaded()) return;
        Snapshot current = snapshot;
        if (current.ready && current.loadedLimit >= limit) return;
        synchronized (LOCK) {
            current = snapshot;
            if (current.ready && current.loadedLimit >= limit) return;
            List<DBHelper.SearchRecallRow> rows = DBHelper.getRecentSearchRecall(context, limit);
            if (Thread.currentThread().isInterrupted()) return;
            Map<String, Pojo> resolved = new HashMap<>();
            Set<String> missing = new HashSet<>();
            List<Entry> recent = new ArrayList<>(rows.size());
            long sequence = 0;
            for (DBHelper.SearchRecallRow row : rows) {
                if (Thread.currentThread().isInterrupted()) return;
                Pojo pojo = resolved.get(row.record);
                if (pojo == null && !missing.contains(row.record)) {
                    pojo = handler.getItemById(row.record);
                    if (pojo == null && row.record.startsWith("app://")) {
                        pojo = handler.resolveRememberedAppHistory(row.record);
                    }
                    if (pojo == null) missing.add(row.record);
                    else resolved.put(row.record, pojo);
                }
                if (pojo != null) {
                    recent.add(new Entry(row.query, row.record, pojo, ++sequence));
                }
            }
            snapshot = build(recent, limit);
        }
    }

    /**
     * A completed full search remembers its genuine provider matches even if nothing is tapped.
     * This runs on the existing QUERY worker, never on the typing/UI thread.
     */
    public static void remember(Context context, DataHandler handler, String query,
                                List<Pojo> results, int maxHistoryResults) {
        String normalized = SearchRecallPolicy.normalize(query);
        int limit = Math.max(0, maxHistoryResults);
        if (normalized.isEmpty() || limit == 0 || Thread.currentThread().isInterrupted()
                || PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean("freeze-history", false)) return;

        LinkedHashMap<String, Pojo> distinct = new LinkedHashMap<>();
        if (results != null) {
            for (Pojo pojo : results) {
                if (pojo == null || TextUtils.isEmpty(pojo.id)
                        || pojo instanceof fr.neamar.kiss.pojo.SearchPojo) continue;
                // Search actions and temporary suggestions are not persistable app destinations.
                distinct.put(pojo.id, pojo);
            }
        }
        List<String> ids = new ArrayList<>(distinct.keySet());
        DBHelper.replaceSearchRecall(context, normalized, ids, limit);
        if (Thread.currentThread().isInterrupted()) return;
        synchronized (LOCK) {
            Snapshot old = snapshot;
            if (!old.ready || old.loadedLimit < limit) {
                // A cold cache will read the freshly committed entries once, off the UI thread.
                // Do not leave a fake in-memory snapshot that could hide older saved searches.
                snapshot = Snapshot.empty();
            } else {
                List<Entry> updated = new ArrayList<>(old.recent.size() + ids.size());
                long nextSequence = old.recent.isEmpty()
                        ? 0L : old.recent.get(old.recent.size() - 1).sequence;
                for (Entry entry : old.recent) {
                    if (!normalized.equals(entry.query)) updated.add(entry);
                }
                for (Map.Entry<String, Pojo> item : distinct.entrySet()) {
                    updated.add(new Entry(normalized, item.getKey(), item.getValue(),
                            ++nextSequence));
                }
                int first = SearchRecallPolicy.firstAllowedIndex(updated.size(), limit);
                snapshot = build(updated.subList(first, updated.size()), limit);
                return;
            }
        }
        warm(context, handler, limit);
    }

    /**
     * Only memory lookups and existing, resolved Pojos. Exact past queries and their prefixes
     * work before the five-second database/provider scan, including after a launcher restart.
     */
    public static List<Pojo> match(MainActivity activity, SharedPreferences prefs,
                                   String typed, int maxHistoryResults, int maxMatches) {
        String normalized = SearchRecallPolicy.normalize(typed);
        Snapshot current = snapshot;
        if (!current.ready || current.recent.isEmpty() || normalized.isEmpty()
                || maxHistoryResults <= 0 || maxMatches <= 0) return Collections.emptyList();

        int first = SearchRecallPolicy.firstAllowedIndex(
                current.recent.size(), maxHistoryResults);
        long earliestSequence = current.recent.get(first).sequence;
        DataHandler dataHandler = KissApplication.getApplication(activity).getDataHandler();
        Set<String> excludedFavorites = dataHandler.getExcludedFavorites();
        Set<String> excludedHistory = dataHandler.getExcludedFromHistory();
        boolean includeExcludedApps = prefs.getBoolean("enable-excluded-apps", false);
        boolean includeFrozen = FrozenAppPreferences.keepSearchable(activity);
        LinkedHashMap<String, Pojo> selected = new LinkedHashMap<>();

        // The exact earlier query takes priority over other historical queries with this prefix.
        select(current.byQuery.get(normalized), earliestSequence, activity, selected,
                maxMatches, excludedFavorites, excludedHistory, includeExcludedApps, includeFrozen);
        for (Map.Entry<String, List<Entry>> entry :
                current.byQuery.tailMap(normalized, false).entrySet()) {
            if (!SearchRecallPolicy.matches(entry.getKey(), normalized)
                    || selected.size() >= maxMatches) break;
            select(entry.getValue(), earliestSequence, activity, selected,
                    maxMatches, excludedFavorites, excludedHistory,
                    includeExcludedApps, includeFrozen);
        }
        return new ArrayList<>(selected.values());
    }

    private static void select(List<Entry> entries, long minSequence, MainActivity activity,
                               LinkedHashMap<String, Pojo> selected, int limit,
                               Set<String> excludedFavorites, Set<String> excludedHistory,
                               boolean includeExcludedApps, boolean includeFrozen) {
        if (entries == null) return;
        for (int i = entries.size() - 1; i >= 0 && selected.size() < limit; i--) {
            Entry entry = entries.get(i);
            Pojo pojo = entry.pojo;
            if (entry.sequence < minSequence || pojo == null || selected.containsKey(entry.id)
                    || excludedFavorites.contains(pojo.getFavoriteId())
                    || excludedHistory.contains(pojo.id)
                    || excludedHistory.contains(pojo.getHistoryId())) continue;
            if (pojo instanceof AppPojo) {
                AppPojo app = (AppPojo) pojo;
                if ((app.isExcluded() && !includeExcludedApps)
                        || (app.isDisabled() && !includeFrozen)) continue;
            } else if (pojo instanceof ShortcutPojo) {
                ShortcutPojo shortcut = (ShortcutPojo) pojo;
                if (shortcut.isDisabled() && !includeFrozen
                        && !ShortcutUtil.isIceBoxPublisher(activity, shortcut.packageName)) {
                    continue;
                }
            }
            selected.put(entry.id, pojo);
        }
    }

    private static Snapshot build(List<Entry> ordered, int limit) {
        List<Entry> safe = Collections.unmodifiableList(new ArrayList<>(ordered));
        NavigableMap<String, List<Entry>> byQuery = new TreeMap<>();
        for (Entry entry : safe) {
            byQuery.computeIfAbsent(entry.query, key -> new ArrayList<>()).add(entry);
        }
        for (Map.Entry<String, List<Entry>> row : byQuery.entrySet()) {
            row.setValue(Collections.unmodifiableList(row.getValue()));
        }
        return new Snapshot(safe, Collections.unmodifiableNavigableMap(byQuery), limit, true);
    }
}
