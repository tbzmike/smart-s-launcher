package fr.neamar.kiss.searcher;

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
import java.util.Locale;
import java.util.Map;
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
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.utils.FrozenAppPreferences;
import fr.neamar.kiss.utils.RecentLaunchTracker;
import fr.neamar.kiss.utils.ShortcutUtil;
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
    private volatile Thread historySeedWorker;
    private volatile Thread historyPreviewWorker;
    private final ThreadPoolExecutor historySeedExecutor = new ThreadPoolExecutor(
            1, 1, 15L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            runnable -> {
                Thread worker = lowPriorityThread(runnable, "smart-s-history-seed");
                historySeedWorker = worker;
                return worker;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ThreadPoolExecutor historyPreviewExecutor = new ThreadPoolExecutor(
            1, 1, 15L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            runnable -> {
                Thread worker = lowPriorityThread(runnable, "smart-s-history-preview");
                historyPreviewWorker = worker;
                return worker;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AtomicLong searchGeneration = new AtomicLong();
    private final AtomicLong completedSearchGeneration = new AtomicLong(-1L);
    private Runnable pendingQuery;

    private SearchHandler() {
        historySeedExecutor.allowCoreThreadTimeOut(true);
        historyPreviewExecutor.allowCoreThreadTimeOut(true);
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
     * Up to 400 already-resolved saved history targets, oldest-to-newest. Only Pojo references are
     * retained; these are provider-owned records, not Result/View/Drawable objects.
     */
    private volatile List<Pojo> historyQuerySeed = Collections.emptyList();

    /** Ready-to-render Home Results retained while QUERY temporarily owns the visible adapter. */
    private volatile List<Result<?>> homeResultSnapshot = Collections.emptyList();
    private volatile Result<?> pendingLaunchedResult;

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

        if (type == Searcher.Type.HISTORY) {
            // A cold Home must show previously resolved history immediately, then let the
            // authoritative search reconcile without blocking the first interactive frame.
            if (!isRefresh && (activity.adapter == null || activity.adapter.isEmpty())) {
                if (!publishWarmHomeResultSnapshot(activity, generation)) {
                    publishHomeHistoryPreview(activity, generation, historyQuerySeed);
                    refreshHistorySeedAndPublish(activity, generation);
                } else {
                    refreshHistorySeed(activity);
                }
            } else {
                refreshHistorySeed(activity);
            }
        }

        if (type == Searcher.Type.QUERY && !isRefresh) {
            // Typing is interactive: stop every History-only helper immediately and publish
            // already-indexed app/shortcut matches in this same UI turn.
            stopHistoryAuxWorkers();
            lastSearchType = Searcher.Type.QUERY;
            lastSearchQuery = query;
            publishImmediateQueryPreview(activity, query, generation);
            startSearch(type, activity, query, false, generation);
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
        } else {
            // The ready Result snapshot is authoritative for the first frame. Refresh only the
            // lightweight seed in the background; the complete HistorySearcher below will publish
            // a real dataset change only if history actually differs.
            refreshHistorySeed(activity);
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
        final WeakReference<MainActivity> activityRef = new WeakReference<>(activity);
        runningSearch = createSearcher(type, activity, query, isRefresh);
        runningSearch.setSearchDoneCallback((searcher, isCancelled) -> {
            if (!isCancelled) {
                completedSearchGeneration.set(generation);
                if (startedType == Searcher.Type.HISTORY && !historyScrollActive) {
                    MainActivity currentActivity = activityRef.get();
                    if (currentActivity != null) refreshHistorySeed(currentActivity);
                }
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
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        int limit = getConfiguredSearchResultCount(prefs);
        if (limit <= 0) return;

        LinkedHashMap<String, Pojo> normal = new LinkedHashMap<>();
        LinkedHashMap<String, Pojo> launch = new LinkedHashMap<>();

        // Ready Home rows first: this instantly surfaces recent messages/contacts/settings without
        // waiting for any provider or SQLite query.
        for (Result<?> result : homeResultSnapshot) {
            if (generation != searchGeneration.get()) return;
            Pojo pojo = result == null ? null : result.getPojo();
            if (pojo == null || !isVisibleByFrozenSearchPolicy(activity, pojo)
                    || !quickMatch(pojo.getName(), q)) continue;
            putImmediatePreview(normal, launch, pojo);
        }

        // Apps and pinned shortcuts are already memory-resident. Scanning their labels is cheap and
        // makes the common "type app name -> launch" path visible immediately.
        DataHandler dataHandler = fr.neamar.kiss.KissApplication.getApplication(activity).getDataHandler();
        fr.neamar.kiss.dataprovider.AppProvider appProvider = dataHandler.getAppProvider();
        List<AppPojo> apps = appProvider == null ? null : appProvider.getPojos();
        if (apps != null) {
            for (AppPojo app : apps) {
                if (generation != searchGeneration.get()) return;
                if (app != null
                        && !app.isExcluded()
                        && (!app.isDisabled() || FrozenAppPreferences.keepSearchable(activity))
                        && quickMatch(app.getName(), q)) {
                    putImmediatePreview(normal, launch, app);
                }
            }
        }
        fr.neamar.kiss.dataprovider.ShortcutsProvider shortcutsProvider =
                dataHandler.getShortcutsProvider();
        List<ShortcutPojo> shortcuts = shortcutsProvider == null ? null : shortcutsProvider.getPojos();
        if (shortcuts != null) {
            for (ShortcutPojo shortcut : shortcuts) {
                if (generation != searchGeneration.get()) return;
                if (shortcut != null
                        && shortcut.isPinned()
                        && (!shortcut.isDisabled()
                        || ShortcutUtil.isIceBoxPublisher(activity, shortcut.packageName)
                        || FrozenAppPreferences.keepSearchable(activity))
                        && quickMatch(shortcut.getName(), q)) {
                    putImmediatePreview(normal, launch, shortcut);
                }
            }
        }

        if (normal.isEmpty() && launch.isEmpty()) return;
        List<Pojo> visible = new ArrayList<>(Math.min(limit, normal.size() + launch.size()));
        for (Pojo pojo : normal.values()) {
            if (visible.size() >= limit) break;
            visible.add(pojo);
        }
        // Preserve the launch-target-at-bottom rule from the first visible frame.
        int launchSlots = Math.max(0, limit - visible.size());
        if (launchSlots == 0 && !launch.isEmpty() && limit > 0) {
            int keepNormal = Math.max(0, limit - Math.min(limit, launch.size()));
            while (visible.size() > keepNormal) visible.remove(visible.size() - 1);
        }
        for (Pojo pojo : launch.values()) {
            if (visible.size() >= limit) break;
            visible.add(pojo);
        }

        if (!visible.isEmpty() && generation == searchGeneration.get()) {
            activity.adapter.updateWithPojos(activity, visible, true, query == null ? "" : query);
        }
    }

    private void putImmediatePreview(Map<String, Pojo> normal, Map<String, Pojo> launch, Pojo pojo) {
        String key = pojo.getClass().getName() + '|' + pojo.id;
        if (pojo instanceof AppPojo || pojo instanceof ShortcutPojo) launch.put(key, pojo);
        else normal.put(key, pojo);
    }

    private boolean isVisibleByFrozenSearchPolicy(@NonNull MainActivity activity,
                                                  @NonNull Pojo pojo) {
        if (!pojo.isDisabled()) return true;
        if (pojo instanceof ShortcutPojo
                && ShortcutUtil.isIceBoxPublisher(
                activity, ((ShortcutPojo) pojo).packageName)) {
            return true;
        }
        return FrozenAppPreferences.keepSearchable(activity);
    }

    private boolean quickMatch(String value, String normalizedQuery) {
        if (value == null || normalizedQuery == null || normalizedQuery.isEmpty()) return false;
        int queryLength = normalizedQuery.length();
        int lastStart = value.length() - queryLength;
        for (int i = 0; i <= lastStart; i++) {
            if (value.regionMatches(true, i, normalizedQuery, 0, queryLength)) return true;
        }
        return false;
    }

    private void stopHistoryAuxWorkers() {
        historySeedExecutor.getQueue().clear();
        historyPreviewExecutor.getQueue().clear();
        Thread seedWorker = historySeedWorker;
        if (seedWorker != null) seedWorker.interrupt();
        Thread previewWorker = historyPreviewWorker;
        if (previewWorker != null) previewWorker.interrupt();
    }

    /**
     * Search the cached saved-history targets independently from the full-search worker. If startup
     * history preloading has not completed yet, this worker builds the seed once before matching.
     */
    private void publishHistoryPreview(@NonNull MainActivity activity, String query, long generation) {
        if (historyScrollActive) return;
        final WeakReference<MainActivity> activityRef = new WeakReference<>(activity);
        final String previewQuery = query == null ? "" : query;
        final List<Pojo> seedSnapshot = historyQuerySeed;

        historyPreviewExecutor.execute(() -> {
            if (historyScrollActive || generation != searchGeneration.get()) return;
            MainActivity currentActivity = activityRef.get();
            if (currentActivity == null) return;

            List<Pojo> seed = seedSnapshot;
            if (seed.isEmpty()) {
                seed = loadHistorySeed(currentActivity);
                if (!seed.isEmpty()) historyQuerySeed = seed;
            }
            if (historyScrollActive || seed.isEmpty() || generation != searchGeneration.get()) return;

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(currentActivity);
            List<Pojo> matches = HistoryPreviewMatcher.match(
                    currentActivity, prefs, previewQuery, seed);
            if (historyScrollActive || matches.isEmpty() || generation != searchGeneration.get()) return;

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
        if (historyScrollActive) return;
        final WeakReference<MainActivity> activityRef = new WeakReference<>(activity);
        historySeedExecutor.execute(() -> {
            if (historyScrollActive) return;
            MainActivity currentActivity = activityRef.get();
            if (currentActivity == null || historyScrollActive) return;
            List<Pojo> seed = loadHistorySeed(currentActivity);
            if (!seed.isEmpty()) {
                historyQuerySeed = seed;
            } else {
                historyQuerySeed = Collections.emptyList();
            }
        });
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
            historyQuerySeed = seed.isEmpty() ? Collections.emptyList() : seed;

            mainHandler.post(() -> {
                if (generation != searchGeneration.get()
                        || completedSearchGeneration.get() == generation) {
                    return;
                }
                MainActivity current = activityRef.get();
                if (current == null || current.isFinishing()) return;
                publishHomeHistoryPreview(current, generation, historyQuerySeed);
            });
        });
    }

    public void rememberHomeResults(@NonNull List<Result<?>> results) {
        if (lastSearchType != Searcher.Type.HISTORY) return;
        List<Result<?>> safe = new ArrayList<>(results.size());
        for (Result<?> result : results) {
            if (canRetainWarmResult(result)) safe.add(result);
        }
        homeResultSnapshot = Collections.unmodifiableList(safe);
    }

    public void rememberLaunchedResult(@NonNull Result<?> result) {
        if (lastSearchType == Searcher.Type.HISTORY || !canRetainWarmResult(result)) return;
        pendingLaunchedResult = result;
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
        activity.adapter.updateResults(activity, restored, false, "");
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
        activity.adapter.updateWithPojos(activity, preview, false, "<history>");
    }

    @NonNull
    private List<Pojo> buildHomeHistoryPreview(@NonNull MainActivity activity,
                                               @NonNull List<Pojo> seed) {
        return buildHomeHistoryPreview(activity, seed, null);
    }

    @NonNull
    private List<Pojo> buildHomeHistoryPreview(@NonNull MainActivity activity,
                                               @NonNull List<Pojo> seed,
                                               @Nullable Pojo explicitMostRecent) {
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
                explicitMostRecent != null ? explicitMostRecent : RecentLaunchTracker.getMostRecent(),
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
     * Resolve at most 400 distinct recent history records. DBHelper RECENCY already returns unique
     * record ids; reversing gives the same oldest-to-newest presentation order used by the history
     * adapter, with the newest/strongest matches at the end.
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

        int requested = HISTORY_PREVIEW_SEED_LIMIT + excluded.size();
        if (historyScrollActive) return Collections.emptyList();
        List<ValuedHistoryRecord> records = DBHelper.getHistory(activity, requested, HistoryMode.RECENCY);
        if (historyScrollActive) return Collections.emptyList();
        List<Pojo> seed = new ArrayList<>(Math.min(HISTORY_PREVIEW_SEED_LIMIT, records.size()));
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
