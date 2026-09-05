package fr.neamar.kiss.searcher;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.HistoryMode;
import fr.neamar.kiss.db.ValuedHistoryRecord;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.utils.RecentLaunchTracker;
import fr.neamar.kiss.utils.fuzzy.SmartMatcher;

public class SearchHandler {

    private static final long QUERY_DEBOUNCE_MS = 16L;
    private static final int HISTORY_PREVIEW_SEED_LIMIT = 400;
    private static volatile SearchHandler instance;

    public static SearchHandler getInstance() {
        if (instance == null) {
            synchronized (SearchHandler.class) {
                if (instance == null) {
                    instance = new SearchHandler();
                }
            }
        }
        return instance;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor historySeedExecutor = new ThreadPoolExecutor(
            1, 1, 15L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            runnable -> new Thread(runnable, "smart-s-history-seed"),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ThreadPoolExecutor historyPreviewExecutor = new ThreadPoolExecutor(
            1, 1, 15L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            runnable -> new Thread(runnable, "smart-s-history-preview"),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AtomicLong searchGeneration = new AtomicLong();
    private final AtomicLong completedSearchGeneration = new AtomicLong(-1L);
    private Runnable pendingQuery;

    private SearchHandler() {
        historySeedExecutor.allowCoreThreadTimeOut(true);
        historyPreviewExecutor.allowCoreThreadTimeOut(true);
    }

    /** Last search type, needed for refresh. */
    private Searcher.Type lastSearchType;

    /** Last search query, needed for refresh. */
    private String lastSearchQuery;

    /** Running search task. */
    private Searcher runningSearch;

    /**
     * Up to 400 already-resolved saved history targets, oldest-to-newest. Only Pojo references are
     * retained; these are provider-owned records, not Result/View/Drawable objects.
     */
    private volatile List<Pojo> historyQuerySeed = Collections.emptyList();

    /**
     * Create search task and execute. Query searches publish a small in-memory history preview on a
     * dedicated worker before the normal provider/database search. This preview worker is separate
     * from Searcher.SEARCH_THREAD, so a cancelled long provider pass cannot delay the first-stage
     * history result.
     */
    public void search(@NonNull Searcher.Type type, @NonNull MainActivity activity,
                       String query, boolean isRefresh) {
        final long generation = searchGeneration.incrementAndGet();
        cancelPendingQuery();
        cancelRunningSearch();

        if (type == Searcher.Type.HISTORY) {
            refreshHistorySeed(activity);
        }

        if (type == Searcher.Type.QUERY && !isRefresh) {
            publishHistoryPreview(activity, query, generation);

            final String scheduledQuery = query;
            final WeakReference<MainActivity> activityRef = new WeakReference<>(activity);
            pendingQuery = () -> {
                pendingQuery = null;
                if (generation != searchGeneration.get()) return;
                MainActivity currentActivity = activityRef.get();
                if (currentActivity != null) {
                    startSearch(type, currentActivity, scheduledQuery, false, generation);
                }
            };
            mainHandler.postDelayed(pendingQuery, QUERY_DEBOUNCE_MS);
            return;
        }

        startSearch(type, activity, query, isRefresh, generation);
    }

    private void startSearch(@NonNull Searcher.Type type, @NonNull MainActivity activity,
                             String query, boolean isRefresh, long generation) {
        if (generation != searchGeneration.get()) return;

        // SmartMatcher caches only immutable preparation for this one search generation. Starting
        // a new operation invalidates it so repeated identical text still observes changed prefs.
        SmartMatcher.beginSearch();
        final Searcher.Type startedType = type;
        final WeakReference<MainActivity> activityRef = new WeakReference<>(activity);
        runningSearch = createSearcher(type, activity, query, isRefresh);
        runningSearch.setSearchDoneCallback((searcher, isCancelled) -> {
            if (!isCancelled) {
                completedSearchGeneration.set(generation);
                if (startedType == Searcher.Type.HISTORY) {
                    MainActivity currentActivity = activityRef.get();
                    if (currentActivity != null) refreshHistorySeed(currentActivity);
                }
            }
            if (runningSearch == searcher) resetRunningSearch();
        });
        // A prior generation may have been cancelled while still queued. Remove its FutureTask
        // before scheduling this generation so it cannot retain stale search/UI state.
        Searcher.purgeCancelledSearches();
        runningSearch.executeOnExecutor(Searcher.SEARCH_THREAD);
    }

    /**
     * Search the cached saved-history targets independently from the full-search worker. If startup
     * history preloading has not completed yet, this worker builds the seed once before matching.
     */
    private void publishHistoryPreview(@NonNull MainActivity activity, String query, long generation) {
        final WeakReference<MainActivity> activityRef = new WeakReference<>(activity);
        final String previewQuery = query == null ? "" : query;
        final List<Pojo> seedSnapshot = historyQuerySeed;

        historyPreviewExecutor.execute(() -> {
            if (generation != searchGeneration.get()) return;
            MainActivity currentActivity = activityRef.get();
            if (currentActivity == null) return;

            List<Pojo> seed = seedSnapshot;
            if (seed.isEmpty()) {
                seed = loadHistorySeed(currentActivity);
                if (!seed.isEmpty()) historyQuerySeed = seed;
            }
            if (seed.isEmpty() || generation != searchGeneration.get()) return;

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(currentActivity);
            List<Pojo> matches = HistoryPreviewMatcher.match(
                    currentActivity, prefs, previewQuery, seed);
            if (matches.isEmpty() || generation != searchGeneration.get()) return;

            int maxResults = getConfiguredSearchResultCount(prefs);
            if (maxResults <= 0) return;
            int from = Math.max(0, matches.size() - maxResults);
            List<Pojo> visible = new ArrayList<>(matches.subList(from, matches.size()));

            mainHandler.post(() -> {
                if (generation != searchGeneration.get()
                        || completedSearchGeneration.get() == generation) {
                    return;
                }
                MainActivity current = activityRef.get();
                if (current == null || current.adapter == null) return;
                current.adapter.updateWithPojos(current, visible, true, previewQuery);
            });
        });
    }

    /** Preload the saved-history search set without delaying the visible History search. */
    private void refreshHistorySeed(@NonNull MainActivity activity) {
        final WeakReference<MainActivity> activityRef = new WeakReference<>(activity);
        historySeedExecutor.execute(() -> {
            MainActivity currentActivity = activityRef.get();
            if (currentActivity == null) return;
            List<Pojo> seed = loadHistorySeed(currentActivity);
            if (!seed.isEmpty()) {
                historyQuerySeed = seed;
            } else {
                historyQuerySeed = Collections.emptyList();
            }
        });
    }

    /**
     * Resolve at most 400 distinct recent history records. DBHelper RECENCY already returns unique
     * record ids; reversing gives the same oldest-to-newest presentation order used by the history
     * adapter, with the newest/strongest matches at the end.
     */
    @NonNull
    private List<Pojo> loadHistorySeed(@NonNull MainActivity activity) {
        DataHandler dataHandler = fr.neamar.kiss.KissApplication.getApplication(activity).getDataHandler();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        Set<String> excluded = new HashSet<>(dataHandler.getExcludedFromHistory());
        if (prefs.getBoolean("exclude-favorites-history", false)) {
            for (Pojo favorite : dataHandler.getFavorites()) {
                if (favorite != null) excluded.add(favorite.id);
            }
        }

        int requested = HISTORY_PREVIEW_SEED_LIMIT + excluded.size();
        List<ValuedHistoryRecord> records = DBHelper.getHistory(activity, requested, HistoryMode.RECENCY);
        List<Pojo> seed = new ArrayList<>(Math.min(HISTORY_PREVIEW_SEED_LIMIT, records.size()));
        Set<String> seen = new HashSet<>();

        for (ValuedHistoryRecord record : records) {
            if (record == null || record.record == null || excluded.contains(record.record)) continue;
            Pojo pojo = dataHandler.getItemById(record.record);
            if (pojo == null) pojo = RecentLaunchTracker.resolve(record.record);
            if (pojo == null || excluded.contains(pojo.id) || !seen.add(pojo.id)) continue;
            seed.add(pojo);
            if (seed.size() >= HISTORY_PREVIEW_SEED_LIMIT) break;
        }

        Collections.reverse(seed);
        return Collections.unmodifiableList(seed);
    }

    private int getConfiguredSearchResultCount(SharedPreferences prefs) {
        try {
            String legacyValue = prefs.getString("number-of-display-elements",
                    String.valueOf(Searcher.DEFAULT_MAX_RESULTS));
            return Math.max(0, Double.valueOf(prefs.getString("number-of-search-results",
                    legacyValue)).intValue());
        } catch (NumberFormatException | ClassCastException e) {
            return Searcher.DEFAULT_MAX_RESULTS;
        }
    }

    /** Cancel last search if still running. */
    public void cancelSearch() {
        searchGeneration.incrementAndGet();
        cancelPendingQuery();
        cancelRunningSearch();
    }

    private void cancelPendingQuery() {
        if (pendingQuery != null) {
            mainHandler.removeCallbacks(pendingQuery);
            pendingQuery = null;
        }
    }

    private void cancelRunningSearch() {
        if (runningSearch != null) {
            runningSearch.cancel(true);
            Searcher.purgeCancelledSearches();
            resetRunningSearch();
        }
    }

    private void resetRunningSearch() {
        runningSearch = null;
    }

    @NonNull
    private Searcher createSearcher(@NonNull Searcher.Type type, @NonNull MainActivity activity,
                                    String query, boolean isRefresh) {
        if (isRefresh && lastSearchType != null) {
            type = this.lastSearchType;
            query = this.lastSearchQuery;
        } else {
            this.lastSearchType = type;
            this.lastSearchQuery = query;
        }

        switch (type) {
            case APPLICATION:
                return new ApplicationsSearcher(activity, isRefresh);
            case QUERY:
                return new QuerySearcher(activity, query, isRefresh,
                        new ArrayList<>(historyQuerySeed));
            case NULL:
                return new NullSearcher(activity);
            case HISTORY:
                return new HistorySearcher(activity, isRefresh);
            case TAGGED:
                return new TagsSearcher(activity, query);
            case UNTAGGED:
                return new UntaggedSearcher(activity);
            default:
                throw new UnsupportedOperationException();
        }
    }

    public Searcher.Type getLastSearchType() {
        return lastSearchType;
    }
}
