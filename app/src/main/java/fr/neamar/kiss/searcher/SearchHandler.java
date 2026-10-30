package fr.neamar.kiss.searcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.utils.FrozenAppPreferences;
import fr.neamar.kiss.utils.RecentLaunchTracker;
import fr.neamar.kiss.utils.fuzzy.SmartMatcher;

public class SearchHandler {

    private static final long QUERY_DEBOUNCE_MS = HistoryFirstSearchPolicy.DEEP_SEARCH_IDLE_DELAY_MS;
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
    private volatile Thread historySeedWorker;
    private final ThreadPoolExecutor historySeedExecutor = new ThreadPoolExecutor(
            1, 1, 15L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            runnable -> {
                Thread worker = lowPriorityThread(runnable, "smart-s-history-seed");
                historySeedWorker = worker;
                return worker;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AtomicLong searchGeneration = new AtomicLong();
    private final AtomicLong completedSearchGeneration = new AtomicLong(-1L);
    private Runnable pendingQuery;

    private SearchHandler() {
        historySeedExecutor.allowCoreThreadTimeOut(true);
    }

    private static Thread lowPriorityThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    }

    /** Last search type, needed for refresh. */
    private Searcher.Type lastSearchType;

    /** Last search query, needed for refresh. */
    private String lastSearchQuery;

    /** Running search task. */
    private Searcher runningSearch;

    /**
     * History scrolling is a render-critical section. While true, Smart S Launcher must not start
     * History DB scans, seed rebuilds or preview work. One refresh is remembered and resumed after
     * the active renderer has been idle.
     */
    private volatile boolean historyScrollActive;
    private boolean pendingHistoryAfterScroll;
    private boolean pendingHistoryRefreshAfterScroll;
    private WeakReference<MainActivity> pendingHistoryActivity = new WeakReference<>(null);

    /**
     * Up to the configured History display limit of already-resolved recent targets,
     * oldest-to-newest. This must follow preferences rather than a fixed 400-item cap.
     */
    private volatile List<Pojo> historyQuerySeed = Collections.emptyList();
    private HistoryPreviewMatcher.Snapshot historyPreviewSnapshot =
            HistoryPreviewMatcher.prepare(Collections.emptyList());
    private boolean publishingHomePreview;

    /** Ready-to-render Home Results retained while QUERY temporarily owns the visible adapter. */
    private volatile List<Result<?>> homeResultSnapshot = Collections.emptyList();
    private volatile Result<?> pendingLaunchedResult;

    /**
     * Filter the configured saved History window in memory in this UI turn. Only the delayed
     * second stage may run provider/database/semantic work; no history reconstruction is queued
     * by a keystroke.
     */
    public void search(@NonNull Searcher.Type type, @NonNull MainActivity activity,
                       String query, boolean isRefresh) {
        final long generation = searchGeneration.incrementAndGet();
        cancelPendingQuery();
        cancelRunningSearch();

        if (type == Searcher.Type.HISTORY && historyScrollActive) {
            // Never lose an authoritative History load. While a fling is active we still defer the
            // expensive DB/provider work, but remember one coalesced request and run it as soon as
            // the viewport becomes idle. Dropping this request could leave Home permanently showing
            // main_empty until some unrelated event happened to trigger another refresh.
            lastSearchType = Searcher.Type.HISTORY;
            lastSearchQuery = query;
            rememberHistoryAfterScroll(activity, isRefresh);
            return;
        }

        if (type == Searcher.Type.QUERY) {
            // Stage 1: ONLY the already-resolved, configured Home/History window.
            // Do not start provider, semantic, or global database scans while typing.
            stopHistoryAuxWorkers();
            lastSearchType = Searcher.Type.QUERY;
            lastSearchQuery = query;
            publishImmediateQueryPreview(activity, query, generation);

            // Stage 2: ONE delayed full search, five seconds after the latest keystroke.
            // Every new query, Home return, pause/cancel or different search type removes
            // this callback; generation checking also protects against stale requests.
            final WeakReference<MainActivity> target = new WeakReference<>(activity);
            final String capturedQuery = query;
            final boolean capturedRefresh = isRefresh;
            pendingQuery = () -> {
                pendingQuery = null;
                MainActivity current = target.get();
                if (generation != searchGeneration.get()
                        || lastSearchType != Searcher.Type.QUERY
                        || !java.util.Objects.equals(capturedQuery, lastSearchQuery)
                        || current == null || current.isFinishing() || current.isDestroyed()) return;
                startSearch(Searcher.Type.QUERY, current, capturedQuery, capturedRefresh, generation);
            };
            mainHandler.postDelayed(pendingQuery, QUERY_DEBOUNCE_MS);
            return;
        }

        startSearch(type, activity, query, isRefresh, generation);
    }

    /**
     * Recover a genuinely empty normal Home without restarting the process.
     *
     * This is deliberately idempotent: if the authoritative History worker is already running we
     * leave it alone instead of repeatedly cancelling/restarting it. If a scroll owns the render
     * critical section, remember one replay for idle just like a normal History request.
     */
    public void ensureHomeHistory(@NonNull MainActivity activity) {
        if (activity.adapter != null && !activity.adapter.isEmpty()) return;

        lastSearchType = Searcher.Type.HISTORY;
        lastSearchQuery = null;

        if (historyScrollActive) {
            rememberHistoryAfterScroll(activity, true);
            return;
        }

        if (runningSearch instanceof HistorySearcher && !runningSearch.isCancelled()) {
            return;
        }

        restoreHomeHistory(activity);
    }

    /**
     * Return from an externally launched QUERY to Home without waiting for the cancelled provider
     * scan to release the single full-search worker. Full provider/history scans remain serialized;
     * only an already-resolved History snapshot is published immediately.
     */
    public void restoreHomeHistory(@NonNull MainActivity activity) {
        final long generation = searchGeneration.incrementAndGet();
        cancelPendingQuery();
        cancelRunningSearch();

        // The renderer must identify the immediate snapshot as History before its adapter callback
        // reaches ForwarderManager/Vertical Cards.
        lastSearchType = Searcher.Type.HISTORY;
        lastSearchQuery = null;

        boolean restoredWarmHome = publishWarmHomeResultSnapshot(activity, generation);
        if (!restoredWarmHome) {
            publishHomeHistoryPreview(activity, generation, historyQuerySeed);
            refreshHistorySeedAndPublish(activity, generation);
        }

        // Keep the complete HistorySearcher on the normal serialized worker. This avoids racing a
        // still-unwinding provider QUERY over shared provider-owned Pojo instances/relevance fields.
        startSearch(Searcher.Type.HISTORY, activity, null, false, generation);
    }

    private void startSearch(@NonNull Searcher.Type type, @NonNull MainActivity activity,
                             String query, boolean isRefresh, long generation) {
        if (generation != searchGeneration.get()) return;
        if (type == Searcher.Type.HISTORY && historyScrollActive) {
            rememberHistoryAfterScroll(activity, isRefresh);
            return;
        }

        // SmartMatcher caches only immutable preparation for this one search generation. Starting
        // a new operation invalidates it so repeated identical text still observes changed prefs.
        SmartMatcher.beginSearch();
        final Searcher.Type startedType = type;
        runningSearch = createSearcher(type, activity, query, isRefresh);
        runningSearch.setSearchDoneCallback((searcher, isCancelled) -> {
            if (!isCancelled) {
                completedSearchGeneration.set(generation);
            }
            if (runningSearch == searcher) resetRunningSearch();
        });
        // A prior generation may have been cancelled while still queued. Remove its FutureTask
        // before scheduling this generation so it cannot retain stale search/UI state.
        Searcher.purgeCancelledSearches();
        runningSearch.executeOnExecutor(
                startedType == Searcher.Type.QUERY ? Searcher.QUERY_THREAD : Searcher.SEARCH_THREAD);
    }

    private void publishImmediateQueryPreview(@NonNull MainActivity activity,
                                              String query, long generation) {
        if (generation != searchGeneration.get() || activity.adapter == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        List<Pojo> matches = HistoryPreviewMatcher.match(activity, prefs, query,
                historyPreviewSnapshot, getConfiguredHistoryResultCount(prefs));
        int limit = getConfiguredSearchResultCount(prefs);
        int from = Math.max(0, matches.size() - limit);
        List<Pojo> visible = limit <= 0 ? Collections.emptyList()
                : new ArrayList<>(matches.subList(from, matches.size()));
        if (generation != searchGeneration.get()) return;
        // Publishing an empty current match set also removes stale previous-query rows.
        activity.adapter.updateWithPojos(activity, visible, true, query == null ? "" : query);
    }

    private void stopHistoryAuxWorkers() {
        historySeedExecutor.getQueue().clear();
        Thread seedWorker = historySeedWorker;
        if (seedWorker != null) seedWorker.interrupt();
    }

    /** Refresh the lightweight History seed independently and publish it only while still current. */
    private void refreshHistorySeedAndPublish(@NonNull MainActivity activity, long generation) {
        if (historyScrollActive) {
            return;
        }
        final WeakReference<MainActivity> activityRef = new WeakReference<>(activity);
        historySeedExecutor.execute(() -> {
            if (historyScrollActive || generation != searchGeneration.get()) return;
            MainActivity currentActivity = activityRef.get();
            if (currentActivity == null) return;

            List<Pojo> seed = loadHistorySeed(currentActivity);
            if (historyScrollActive || generation != searchGeneration.get()) return;
            mainHandler.post(() -> {
                if (generation != searchGeneration.get()
                        || completedSearchGeneration.get() == generation) {
                    return;
                }
                MainActivity current = activityRef.get();
                if (current == null || current.isFinishing()) return;
                rememberHistoryPojos(current, seed, null);
                publishHomeHistoryPreview(current, generation, historyQuerySeed);
            });
        });
    }

    public void rememberHomeResults(@NonNull Context context, @NonNull List<Result<?>> results) {
        if (lastSearchType != Searcher.Type.HISTORY) return;
        if (!publishingHomePreview) {
            // Keep every saved row type, including contacts omitted from the smaller
            // ready-Result cache and notification rows not present in launch-history SQL. Never re-resolve these IDs from SQLite while typing.
            List<Pojo> saved = new ArrayList<>(results.size());
            for (Result<?> result : results) {
                if (result != null && result.getPojo() != null) saved.add(result.getPojo());
            }
            rememberHistoryPojos(context, saved, null);
        }
        List<Result<?>> safe = new ArrayList<>(results.size());
        for (Result<?> result : results) {
            if (canRetainWarmResult(result)) safe.add(result);
        }
        homeResultSnapshot = Collections.unmodifiableList(safe);
    }

    public void rememberLaunchedResult(@NonNull Context context, @NonNull Result<?> result) {
        // The exact successful launch enters the bounded memory window before returning Home.
        rememberHistoryPojos(context, historyQuerySeed, result.getPojo());
        if (lastSearchType == Searcher.Type.HISTORY || !canRetainWarmResult(result)) return;
        pendingLaunchedResult = result;
    }

    private void rememberHistoryPojos(@NonNull Context context, @NonNull List<Pojo> saved,
                                      @Nullable Pojo mostRecent) {
        List<Pojo> selected = selectSavedHistory(context, saved, mostRecent);
        historyQuerySeed = Collections.unmodifiableList(new ArrayList<>(selected));
        historyPreviewSnapshot = HistoryPreviewMatcher.prepare(historyQuerySeed);
    }

    private boolean canRetainWarmResult(Result<?> result) {
        return result instanceof fr.neamar.kiss.result.AppResult
                || result instanceof fr.neamar.kiss.result.ShortcutsResult
                || result instanceof fr.neamar.kiss.result.SettingsResult
                || result instanceof fr.neamar.kiss.result.PhoneResult
                || result instanceof fr.neamar.kiss.result.CommunicationResult;
    }

    private boolean publishWarmHomeResultSnapshot(@NonNull MainActivity activity, long generation) {
        if (generation != searchGeneration.get() || activity.adapter == null || activity.isFinishing()) {
            return false;
        }

        LinkedHashMap<String, Result<?>> readyById = new LinkedHashMap<>();
        for (Result<?> result : homeResultSnapshot) {
            if (result == null || result.getPojo() == null) continue;
            readyById.put(resultKey(result.getPojo()), result);
        }
        Result<?> launched = pendingLaunchedResult;
        pendingLaunchedResult = null;
        Pojo launchedPojo = launched == null ? null : launched.getPojo();
        if (launchedPojo != null) {
            // LinkedHashMap.put() does not move an existing key. Explicit remove + put keeps the
            // warm snapshot itself oldest-to-newest, and the explicit newest override below makes
            // the first frame after returning from Search deterministic as well.
            String launchedKey = resultKey(launchedPojo);
            readyById.remove(launchedKey);
            readyById.put(launchedKey, launched);
        }
        if (readyById.isEmpty()) return false;

        List<Pojo> seed = new ArrayList<>(readyById.size());
        for (Result<?> result : readyById.values()) seed.add(result.getPojo());
        List<Pojo> selected = buildHomeHistoryPreview(activity, seed, launchedPojo);
        if (selected.isEmpty()) return false;

        List<Result<?>> restored = new ArrayList<>(selected.size());
        for (Pojo pojo : selected) {
            Result<?> result = readyById.get(resultKey(pojo));
            if (result != null) restored.add(result);
        }
        if (restored.isEmpty()) return false;

        homeResultSnapshot = Collections.unmodifiableList(new ArrayList<>(restored));
        publishingHomePreview = true;
        try {
            activity.adapter.updateResults(activity, restored, false, "");
        } finally {
            publishingHomePreview = false;
        }
        return true;
    }

    private String resultKey(Pojo pojo) {
        if (pojo == null) return "<null>";
        return pojo.getClass().getName() + '|' + pojo.id;
    }

    /** Publish only resolved cached/recent History rows; never start another provider scan here. */
    private void publishHomeHistoryPreview(@NonNull MainActivity activity, long generation,
                                           @NonNull List<Pojo> seed) {
        if (generation != searchGeneration.get() || activity.adapter == null || activity.isFinishing()) {
            return;
        }

        List<Pojo> preview = buildHomeHistoryPreview(activity, seed);
        if (preview.isEmpty()) {
            // An empty Home is preferable to a stale QUERY tree. The authoritative History search
            // is already queued and will replace this as soon as the serialized worker is free.
            activity.adapter.clear();
            return;
        }
        publishingHomePreview = true;
        try {
            activity.adapter.updateWithPojos(activity, preview, false, "<history>");
        } finally {
            publishingHomePreview = false;
        }
    }

    @NonNull
    private List<Pojo> buildHomeHistoryPreview(@NonNull Context activity,
                                               @NonNull List<Pojo> seed) {
        return buildHomeHistoryPreview(activity, seed, null);
    }

    @NonNull
    private List<Pojo> buildHomeHistoryPreview(@NonNull Context activity,
                                               @NonNull List<Pojo> seed,
                                               @Nullable Pojo explicitMostRecent) {
        return selectSavedHistory(activity, seed, explicitMostRecent != null
                ? explicitMostRecent : RecentLaunchTracker.getMostRecent());
    }

    private List<Pojo> selectSavedHistory(@NonNull Context activity, @NonNull List<Pojo> seed,
                                         @Nullable Pojo mostRecent) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        int maxResults = getConfiguredHistoryResultCount(prefs);
        if (maxResults <= 0) return Collections.emptyList();

        DataHandler dataHandler = fr.neamar.kiss.KissApplication.getApplication(activity).getDataHandler();
        Set<String> excluded = new HashSet<>(dataHandler.getExcludedFromHistory());
        if (prefs.getBoolean("exclude-favorites-history", false)) {
            for (Pojo favorite : dataHandler.getFavoritesIncludingDisabled()) {
                if (favorite == null) continue;
                excluded.add(favorite.id);
                String historyId = favorite.getHistoryId();
                if (historyId != null) excluded.add(historyId);
            }
        }
        return HomeHistoryWindow.select(
                seed,
                mostRecent,
                maxResults,
                pojo -> pojo == null ? null : pojo.getHistoryId(),
                pojo -> {
                    if (pojo == null) return false;
                    String historyId = pojo.getHistoryId();
                    if (historyId == null || excluded.contains(historyId) || excluded.contains(pojo.id)) {
                        return false;
                    }
                    return FrozenAppPreferences.keepInHistoryAndFavorites(activity, pojo);
                });
    }

    /**
     * Resolve up to the current user's History setting, without a hard-coded limit.
     * RECENCY returns unique IDs, reversed for the oldest-to-newest Home order.
     */
    @NonNull
    private List<Pojo> loadHistorySeed(@NonNull MainActivity activity) {
        if (historyScrollActive) return Collections.emptyList();
        DataHandler dataHandler = fr.neamar.kiss.KissApplication.getApplication(activity).getDataHandler();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        Set<String> excluded = new HashSet<>(dataHandler.getExcludedFromHistory());
        if (prefs.getBoolean("exclude-favorites-history", false)) {
            for (Pojo favorite : dataHandler.getFavoritesIncludingDisabled()) {
                if (favorite != null) excluded.add(favorite.id);
            }
        }

        int configuredLimit = HistoryFirstSearchPolicy.seedLimit(
                getConfiguredHistoryResultCount(prefs));
        if (configuredLimit <= 0) {
            return Collections.emptyList();
        }
        int requested = (int) Math.min(Integer.MAX_VALUE,
                (long) configuredLimit + excluded.size());
        if (historyScrollActive) return Collections.emptyList();
        List<ValuedHistoryRecord> records = DBHelper.getHistory(activity, requested, HistoryMode.RECENCY);
        if (historyScrollActive) return Collections.emptyList();
        List<Pojo> seed = new ArrayList<>(Math.min(configuredLimit, records.size()));
        Set<String> seen = new HashSet<>();

        for (ValuedHistoryRecord record : records) {
            if (historyScrollActive) return Collections.emptyList();
            if (record == null || record.record == null || excluded.contains(record.record)) continue;
            Pojo pojo = dataHandler.getItemById(record.record);
            if (pojo == null) pojo = RecentLaunchTracker.resolve(record.record);
            if (pojo == null) pojo = dataHandler.resolveRememberedAppHistory(record.record);
            if (pojo == null || excluded.contains(pojo.id)
                    || !FrozenAppPreferences.keepInHistoryAndFavorites(activity, pojo)
                    || !seen.add(pojo.id)) continue;
            seed.add(pojo);
            if (seed.size() >= configuredLimit) break;
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

    private int getConfiguredHistoryResultCount(SharedPreferences prefs) {
        try {
            String legacyValue = prefs.getString("number-of-display-elements",
                    String.valueOf(Searcher.DEFAULT_MAX_RESULTS));
            return Math.max(0, Double.valueOf(prefs.getString("number-of-history-results",
                    legacyValue)).intValue());
        } catch (NumberFormatException | ClassCastException e) {
            return Searcher.DEFAULT_MAX_RESULTS;
        }
    }

    public void onHistoryScrollStarted(@NonNull MainActivity activity) {
        if (historyScrollActive) return;
        historyScrollActive = true;

        // HARD scroll freeze: kill every History helper and the full History search immediately.
        // If the cancelled task was the authoritative load for Home, remember one refresh for idle
        // rather than losing it. Existing visible rows remain untouched during the gesture.
        stopHistoryAuxWorkers();

        if (runningSearch instanceof HistorySearcher) {
            rememberHistoryAfterScroll(activity, true);
            searchGeneration.incrementAndGet();
            ((HistorySearcher) runningSearch).cancelDatabaseWork();
            runningSearch.cancel(true);
            Searcher.purgeCancelledSearches();
            resetRunningSearch();
        }
    }

    public void onHistoryScrollIdle(@NonNull MainActivity activity) {
        if (!historyScrollActive) return;
        historyScrollActive = false;

        boolean replay = pendingHistoryAfterScroll;
        boolean refresh = pendingHistoryRefreshAfterScroll;
        MainActivity replayActivity = pendingHistoryActivity.get();
        clearPendingHistoryAfterScroll();

        if (!replay) return;
        MainActivity target = replayActivity != null ? replayActivity : activity;
        mainHandler.post(() -> {
            if (historyScrollActive || target.isFinishing() || target.isDestroyed()) return;
            if (lastSearchType != Searcher.Type.HISTORY) return;
            search(Searcher.Type.HISTORY, target, lastSearchQuery, refresh);
        });
    }

    /**
     * Lifecycle escape hatch for the case where Android pauses Home while a fling is still marked
     * active. The matching idle callback is not guaranteed once the window loses focus; leaving the
     * singleton flag true made every later History load get deferred forever.
     */
    public void onLauncherPaused() {
        historyScrollActive = false;
        clearPendingHistoryAfterScroll();
        if (lastSearchType == Searcher.Type.QUERY) {
            // An inactive launcher must not wake up to run the five-second provider scan.
            // Resume re-arms it only if the QUERY view is still active.
            searchGeneration.incrementAndGet();
            cancelPendingQuery();
            cancelRunningSearch();
            stopHistoryAuxWorkers();
        }
    }

    /** Re-arm a postponed QUERY only after Home has returned to the foreground. */
    public void onLauncherResumed(@NonNull MainActivity activity) {
        if (lastSearchType == Searcher.Type.QUERY
                && lastSearchQuery != null && !lastSearchQuery.trim().isEmpty()
                && pendingQuery == null && runningSearch == null) {
            search(Searcher.Type.QUERY, activity, lastSearchQuery, true);
        }
    }

    public boolean isHistoryScrollActive() {
        return historyScrollActive;
    }

    public void deferHistoryUntilIdle(@NonNull MainActivity activity, boolean isRefresh) {
        lastSearchType = Searcher.Type.HISTORY;
        lastSearchQuery = null;
        rememberHistoryAfterScroll(activity, isRefresh);
    }

    private void rememberHistoryAfterScroll(@NonNull MainActivity activity, boolean isRefresh) {
        pendingHistoryAfterScroll = true;
        pendingHistoryRefreshAfterScroll |= isRefresh;
        pendingHistoryActivity = new WeakReference<>(activity);
    }

    private void clearPendingHistoryAfterScroll() {
        pendingHistoryAfterScroll = false;
        pendingHistoryRefreshAfterScroll = false;
        pendingHistoryActivity = new WeakReference<>(null);
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
            if (runningSearch instanceof HistorySearcher) {
                ((HistorySearcher) runningSearch).cancelDatabaseWork();
            }
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
