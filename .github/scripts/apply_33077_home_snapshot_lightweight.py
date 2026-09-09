from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}\n--- needle ---\n{old[:500]}")
    p.write_text(text.replace(old, new, 1))


# Version bump.
replace_once(
    "app/build.gradle",
    '''        // Smart S Launcher 3.30.76 - horizontal-baseline scrolling and stable 3D wheel\n        versionCode 504\n        versionName "3.30.76"''',
    '''        // Smart S Launcher 3.30.77 - warm Home snapshots and lightweight vertical scrolling\n        versionCode 505\n        versionName "3.30.77"''')

# RecordAdapter: cache render configuration outside the per-row bind path, retain ready Results,
# and reuse by stable identity/state instead of Java Pojo object identity.
record = "app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java"
replace_once(record,
    'import android.text.TextWatcher;\nimport android.util.TypedValue;',
    'import android.text.TextWatcher;\nimport android.os.SystemClock;\nimport android.util.TypedValue;')
replace_once(record,
    'public class RecordAdapter extends BaseAdapter implements SectionIndexer {\n    private static final String TAG = RecordAdapter.class.getSimpleName();',
    '''public class RecordAdapter extends BaseAdapter implements SectionIndexer {\n    private static final String TAG = RecordAdapter.class.getSimpleName();\n    private static final long RENDER_CONFIG_CACHE_MS = 1500L;''')
replace_once(record,
    '''    private String[] sections = new String[0];\n    private String lastRenderedQuery = null;''',
    '''    private String[] sections = new String[0];\n    private String lastRenderedQuery = null;\n    private long renderConfigLoadedAt;\n    private String cachedOverflowMode = TextOverflowMode.AUTO_SCROLL;\n    private String cachedHistoryLayout = "vertical";\n    private int cachedVerticalStyleSignature = Integer.MIN_VALUE;\n    private int cachedHistoryWidthPercent = 100;''')
replace_once(record,
    '''    public View getView(int position, View convertView, @NonNull ViewGroup parent) {\n        Result<?> result = getItem(position);\n        View view = result.display(parent.getContext(), convertView, parent, fuzzyScore);''',
    '''    public View getView(int position, View convertView, @NonNull ViewGroup parent) {\n        Context renderContext = parent.getContext();\n        refreshRenderConfigIfNeeded(renderContext);\n        Result<?> result = getItem(position);\n        View view = result.display(renderContext, convertView, parent, fuzzyScore);''')
replace_once(record,
    '        String overflowMode = TextOverflowMode.effectiveMode(parent.getContext());',
    '        String overflowMode = cachedOverflowMode;')
replace_once(record,
    '''        if (parent instanceof AbsListView) {\n            Context context = parent.getContext();\n            TileVisualStyle.apply(view, result, context);\n            if (isVerticalHistory(context)) {\n                int signature = verticalStyleSignature(context);''',
    '''        if (parent instanceof AbsListView) {\n            Context context = renderContext;\n            TileVisualStyle.apply(view, result, context);\n            if (isVerticalHistory()) {\n                int signature = cachedVerticalStyleSignature;''')
replace_once(record,
    '                applyVerticalHistoryWidth(view, parent, context);',
    '                applyVerticalHistoryWidth(view, parent, context, cachedHistoryWidthPercent);')
replace_once(record,
    '''    private void applyVerticalHistoryWidth(View row, ViewGroup parent, Context context) {\n        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);\n        int widthPercent = safePercent(prefs, "smart-list-card-width-percent", 100, 48, 400);''',
    '''    private void applyVerticalHistoryWidth(View row, ViewGroup parent, Context context, int widthPercent) {''')
replace_once(record,
    '''    private int verticalStyleSignature(Context context) {\n        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);''',
    '''    private void refreshRenderConfigIfNeeded(Context context) {\n        long now = SystemClock.uptimeMillis();\n        if (cachedVerticalStyleSignature != Integer.MIN_VALUE\n                && now - renderConfigLoadedAt < RENDER_CONFIG_CACHE_MS) return;\n        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);\n        cachedHistoryLayout = prefs.getString("smart-history-layout", "vertical");\n        if (cachedHistoryLayout == null) cachedHistoryLayout = "vertical";\n        cachedOverflowMode = TextOverflowMode.effectiveMode(context);\n        cachedHistoryWidthPercent = safePercent(\n                prefs, "smart-list-card-width-percent", 100, 48, 400);\n        cachedVerticalStyleSignature = verticalStyleSignature(context, cachedOverflowMode);\n        renderConfigLoadedAt = now;\n    }\n\n    private void invalidateRenderConfig() {\n        renderConfigLoadedAt = 0L;\n        cachedVerticalStyleSignature = Integer.MIN_VALUE;\n    }\n\n    private int verticalStyleSignature(Context context, String overflowMode) {\n        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);''')
replace_once(record,
    '        result = 31 * result + TextOverflowMode.effectiveMode(context).hashCode();',
    '        result = 31 * result + overflowMode.hashCode();')
replace_once(record,
    '''    private boolean isVerticalHistory(Context context) {\n        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);\n        String layout = prefs.getString("smart-history-layout", "vertical");\n        return ("vertical".equals(layout) || "wheel_3d".equals(layout))\n                && SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY;\n    }''',
    '''    private boolean isVerticalHistory() {\n        return ("vertical".equals(cachedHistoryLayout) || "wheel_3d".equals(cachedHistoryLayout))\n                && SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY;\n    }''')
replace_once(record,
    '''    /** Snapshot the already-loaded rows for a cheap history-first query stage. */\n    @NonNull\n    public List<Pojo> snapshotPojos() {''',
    '''    /** Snapshot the already-loaded Result objects so Home can restore ready icons/state. */\n    @NonNull\n    public List<Result<?>> snapshotResults() {\n        return new ArrayList<>(results);\n    }\n\n    /** Snapshot the already-loaded rows for a cheap history-first query stage. */\n    @NonNull\n    public List<Pojo> snapshotPojos() {''')
replace_once(record,
    '''    public void updateWithPojos(@NonNull Context context, @NonNull List<Pojo> pojos, boolean isRefresh, String query) {\n        Map<Pojo, Result<?>> existingResults = new HashMap<>(Math.max(16, results.size() * 2));\n        for (Result<?> result : results) existingResults.put(result.getPojo(), result);\n        List<Result<?>> updatedResults = new ArrayList<>(pojos.size());\n        for (Pojo pojo : pojos) {\n            if (pojo == null) continue;\n            Result<?> existing = existingResults.get(pojo);\n            if (existing != null) updatedResults.add(existing);\n            else if (pojo instanceof CommunicationPojo) updatedResults.add(new CommunicationResult((CommunicationPojo) pojo));\n            else updatedResults.add(Result.fromPojo(parent, pojo));\n        }\n        updateResults(context, updatedResults, isRefresh, query);\n    }''',
    '''    public void updateWithPojos(@NonNull Context context, @NonNull List<Pojo> pojos, boolean isRefresh, String query) {\n        Map<String, Result<?>> existingResults = new HashMap<>(Math.max(16, results.size() * 2));\n        for (Result<?> result : results) {\n            if (result != null && result.getPojo() != null) {\n                existingResults.put(reuseKey(result.getPojo()), result);\n            }\n        }\n        List<Result<?>> updatedResults = new ArrayList<>(pojos.size());\n        for (Pojo pojo : pojos) {\n            if (pojo == null) continue;\n            Result<?> existing = existingResults.get(reuseKey(pojo));\n            if (existing != null && samePojoState(existing.getPojo(), pojo)) {\n                updatedResults.add(existing);\n            } else if (pojo instanceof CommunicationPojo) {\n                updatedResults.add(new CommunicationResult((CommunicationPojo) pojo));\n            } else {\n                updatedResults.add(Result.fromPojo(parent, pojo));\n            }\n        }\n        updateResults(context, updatedResults, isRefresh, query);\n    }\n\n    private String reuseKey(Pojo pojo) {\n        if (pojo == null) return "<null>";\n        return pojo.getClass().getName() + '|' + pojo.id;\n    }''')
replace_once(record,
    '''    public void updateResults(@NonNull Context context, List<Result<?>> updatedResults, boolean isRefresh, String query) {\n        String normalizedQuery = query == null ? "" : query;\n        if (sameVisibleState(updatedResults, normalizedQuery)) return;''',
    '''    public void updateResults(@NonNull Context context, List<Result<?>> updatedResults, boolean isRefresh, String query) {\n        String normalizedQuery = query == null ? "" : query;\n        invalidateRenderConfig();\n        if (sameVisibleState(updatedResults, normalizedQuery)) return;''')
replace_once(record,
    '''        notifyDataSetChanged();\n        if (isRefresh) parent.temporarilyDisableTranscriptMode();\n        parent.afterListChange();''',
    '''        notifyDataSetChanged();\n        if (SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY\n                && (normalizedQuery.isEmpty() || "<history>".equals(normalizedQuery))) {\n            SearchHandler.getInstance().rememberHomeResults(snapshotResults());\n        }\n        if (isRefresh) parent.temporarilyDisableTranscriptMode();\n        parent.afterListChange();''')

# SearchHandler: keep ready Home Results while query results own the adapter, restore them first,
# and merge the successfully launched Result so its already-loaded icon is immediately reusable.
search = "app/src/main/java/fr/neamar/kiss/searcher/SearchHandler.java"
replace_once(search,
    '''import java.util.HashSet;\nimport java.util.List;\nimport java.util.Set;''',
    '''import java.util.HashSet;\nimport java.util.LinkedHashMap;\nimport java.util.List;\nimport java.util.Map;\nimport java.util.Set;''')
replace_once(search,
    'import fr.neamar.kiss.pojo.Pojo;\nimport fr.neamar.kiss.utils.RecentLaunchTracker;',
    'import fr.neamar.kiss.pojo.Pojo;\nimport fr.neamar.kiss.result.Result;\nimport fr.neamar.kiss.utils.RecentLaunchTracker;')
replace_once(search,
    '''    private volatile List<Pojo> historyQuerySeed = Collections.emptyList();''',
    '''    private volatile List<Pojo> historyQuerySeed = Collections.emptyList();\n\n    /** Ready-to-render Home Results retained while QUERY temporarily owns the visible adapter. */\n    private volatile List<Result<?>> homeResultSnapshot = Collections.emptyList();\n    private volatile Result<?> pendingLaunchedResult;''')
replace_once(search,
    '''        publishHomeHistoryPreview(activity, generation, historyQuerySeed);\n        refreshHistorySeedAndPublish(activity, generation);\n\n        // Keep the complete HistorySearcher on the normal serialized worker.''',
    '''        boolean restoredWarmHome = publishWarmHomeResultSnapshot(activity, generation);\n        if (!restoredWarmHome) {\n            publishHomeHistoryPreview(activity, generation, historyQuerySeed);\n            refreshHistorySeedAndPublish(activity, generation);\n        } else {\n            // The ready Result snapshot is authoritative for the first frame. Refresh only the\n            // lightweight seed in the background; the complete HistorySearcher below will publish\n            // a real dataset change only if history actually differs.\n            refreshHistorySeed(activity);\n        }\n\n        // Keep the complete HistorySearcher on the normal serialized worker.''')
replace_once(search,
    '''    /** Publish only resolved cached/recent History rows; never start another provider scan here. */\n    private void publishHomeHistoryPreview(@NonNull MainActivity activity, long generation,''',
    '''    public void rememberHomeResults(@NonNull List<Result<?>> results) {\n        if (lastSearchType != Searcher.Type.HISTORY) return;\n        homeResultSnapshot = Collections.unmodifiableList(new ArrayList<>(results));\n    }\n\n    public void rememberLaunchedResult(@NonNull Result<?> result) {\n        if (lastSearchType == Searcher.Type.HISTORY) return;\n        pendingLaunchedResult = result;\n    }\n\n    private boolean publishWarmHomeResultSnapshot(@NonNull MainActivity activity, long generation) {\n        if (generation != searchGeneration.get() || activity.adapter == null || activity.isFinishing()) {\n            return false;\n        }\n\n        LinkedHashMap<String, Result<?>> readyById = new LinkedHashMap<>();\n        for (Result<?> result : homeResultSnapshot) {\n            if (result == null || result.getPojo() == null) continue;\n            readyById.put(resultKey(result.getPojo()), result);\n        }\n        Result<?> launched = pendingLaunchedResult;\n        pendingLaunchedResult = null;\n        if (launched != null && launched.getPojo() != null) {\n            readyById.put(resultKey(launched.getPojo()), launched);\n        }\n        if (readyById.isEmpty()) return false;\n\n        List<Pojo> seed = new ArrayList<>(readyById.size());\n        for (Result<?> result : readyById.values()) seed.add(result.getPojo());\n        List<Pojo> selected = buildHomeHistoryPreview(activity, seed);\n        if (selected.isEmpty()) return false;\n\n        List<Result<?>> restored = new ArrayList<>(selected.size());\n        for (Pojo pojo : selected) {\n            Result<?> result = readyById.get(resultKey(pojo));\n            if (result != null) restored.add(result);\n        }\n        if (restored.isEmpty()) return false;\n\n        homeResultSnapshot = Collections.unmodifiableList(new ArrayList<>(restored));\n        activity.adapter.updateResults(activity, restored, false, "<history>");\n        return true;\n    }\n\n    private String resultKey(Pojo pojo) {\n        if (pojo == null) return "<null>";\n        return pojo.getClass().getName() + '|' + pojo.id;\n    }\n\n    /** Publish only resolved cached/recent History rows; never start another provider scan here. */\n    private void publishHomeHistoryPreview(@NonNull MainActivity activity, long generation,''')
replace_once(search,
    '''            runnable -> new Thread(runnable, "smart-s-history-seed"),''',
    '''            runnable -> lowPriorityThread(runnable, "smart-s-history-seed"),''')
replace_once(search,
    '''            runnable -> new Thread(runnable, "smart-s-history-preview"),''',
    '''            runnable -> lowPriorityThread(runnable, "smart-s-history-preview"),''')
replace_once(search,
    '''    private SearchHandler() {\n        historySeedExecutor.allowCoreThreadTimeOut(true);\n        historyPreviewExecutor.allowCoreThreadTimeOut(true);\n    }''',
    '''    private SearchHandler() {\n        historySeedExecutor.allowCoreThreadTimeOut(true);\n        historyPreviewExecutor.allowCoreThreadTimeOut(true);\n    }\n\n    private static Thread lowPriorityThread(Runnable runnable, String name) {\n        Thread thread = new Thread(runnable, name);\n        thread.setPriority(Thread.MIN_PRIORITY);\n        return thread;\n    }''')

# Result: remember only successful history-eligible launches, after the history record has been saved.
result = "app/src/main/java/fr/neamar/kiss/result/Result.java"
replace_once(result,
    '''        if (canAddToHistory()) {\n            KissApplication.getApplication(context).getDataHandler().addToHistory(pojo.getHistoryId());\n            UniversalHistoryTimestamp.invalidateStats();\n        }''',
    '''        if (canAddToHistory()) {\n            KissApplication.getApplication(context).getDataHandler().addToHistory(pojo.getHistoryId());\n            SearchHandler.getInstance().rememberLaunchedResult(this);\n            UniversalHistoryTimestamp.invalidateStats();\n        }''')

# Vertical Cards: retain the prior Home View tree during QUERY and reattach unchanged cards on return.
cards = "app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java"
replace_once(cards,
    '''    private final Map<String, String> activeQueryCardSignatures = new HashMap<>();''',
    '''    private final Map<String, String> activeQueryCardSignatures = new HashMap<>();\n    private final Map<String, View> warmHistoryCards = new LinkedHashMap<>();\n    private int warmHistoryScrollY;''')
replace_once(cards,
    '''        activeQueryCardSignatures.clear();\n        accentCache.clear();''',
    '''        activeQueryCardSignatures.clear();\n        warmHistoryCards.clear();\n        accentCache.clear();''')
replace_once(cards,
    '''        if (activeQuery && allowActiveQueryReuse && previouslyRenderedActiveQuery) {\n            reconcileActiveQueryCards();\n        } else {\n            Map<String, NotificationHistoryRecord> latestNotifications =\n                    !activeQuery && prefs.getBoolean("enable-notification-history", false)\n                            ? SmartStateStore.queryLatestNotificationsByPackage(mainActivity)\n                            : Collections.emptyMap();\n            activeQueryCardSignatures.clear();\n            column.removeAllViews();\n\n            int count = mainActivity.adapter.getCount();\n            for (int position = 0; position < count; position++) {\n                Result<?> result = mainActivity.adapter.getItem(position);\n                View source = mainActivity.adapter.getView(position, null, column);\n                View item = createCardItem(source, result, position, latestNotifications);\n                column.addView(item);\n                if (activeQuery) {\n                    activeQueryCardSignatures.put(\n                            result.getPojoId(), activeQueryCardSignature(result));\n                }\n            }\n        }''',
    '''        if (activeQuery && allowActiveQueryReuse && previouslyRenderedActiveQuery) {\n            reconcileActiveQueryCards();\n        } else {\n            Map<String, NotificationHistoryRecord> latestNotifications =\n                    !activeQuery && prefs.getBoolean("enable-notification-history", false)\n                            ? SmartStateStore.queryLatestNotificationsByPackage(mainActivity)\n                            : Collections.emptyMap();\n            if (activeQuery && !previouslyRenderedActiveQuery) captureWarmHistoryCards();\n            boolean restoredWarmHistory = !activeQuery && previouslyRenderedActiveQuery\n                    && restoreWarmHistoryCards(latestNotifications);\n            activeQueryCardSignatures.clear();\n\n            if (!restoredWarmHistory) {\n                column.removeAllViews();\n                int count = mainActivity.adapter.getCount();\n                for (int position = 0; position < count; position++) {\n                    Result<?> result = mainActivity.adapter.getItem(position);\n                    View source = mainActivity.adapter.getView(position, null, column);\n                    View item = createCardItem(source, result, position, latestNotifications);\n                    column.addView(item);\n                    if (activeQuery) {\n                        activeQueryCardSignatures.put(\n                                result.getPojoId(), activeQueryCardSignature(result));\n                    }\n                }\n            } else {\n                animateHistoryItems = false;\n            }\n        }''')
replace_once(cards,
    '''    /**\n     * Active search used to destroy and recreate the complete Vertical Cards hierarchy after every\n     * debounce. Reconcile by stable POJO identity instead: unchanged cards keep their Views, click\n     * listeners and drawables; only inserted/changed results are materialized from the adapter.\n     */\n    private void reconcileActiveQueryCards() {''',
    '''    private void captureWarmHistoryCards() {\n        warmHistoryCards.clear();\n        warmHistoryScrollY = scroller == null ? 0 : scroller.getScrollY();\n        if (column == null) return;\n        for (int i = 0; i < column.getChildCount(); i++) {\n            View child = column.getChildAt(i);\n            Object tag = child.getTag();\n            if (tag instanceof String) warmHistoryCards.put((String) tag, child);\n        }\n    }\n\n    private boolean restoreWarmHistoryCards(\n            Map<String, NotificationHistoryRecord> latestNotifications) {\n        if (column == null || mainActivity.adapter == null || warmHistoryCards.isEmpty()) return false;\n        Map<String, View> available = new LinkedHashMap<>(warmHistoryCards);\n        column.removeAllViews();\n        int count = mainActivity.adapter.getCount();\n        for (int position = 0; position < count; position++) {\n            Result<?> result = mainActivity.adapter.getItem(position);\n            View item = available.remove(result.getPojoId());\n            if (item == null) {\n                View source = mainActivity.adapter.getView(position, null, column);\n                item = createCardItem(source, result, position, latestNotifications);\n            } else if (item.getParent() instanceof ViewGroup) {\n                ((ViewGroup) item.getParent()).removeView(item);\n            }\n            column.addView(item);\n        }\n        warmHistoryCards.clear();\n        column.requestLayout();\n        column.invalidate();\n        int restoreY = warmHistoryScrollY;\n        scroller.post(() -> {\n            if (scroller == null || column == null) return;\n            int max = Math.max(0, column.getHeight() - scroller.getHeight());\n            scroller.scrollTo(0, Math.max(0, Math.min(max, restoreY)));\n        });\n        return true;\n    }\n\n    /**\n     * Active search used to destroy and recreate the complete Vertical Cards hierarchy after every\n     * debounce. Reconcile by stable POJO identity instead: unchanged cards keep their Views, click\n     * listeners and drawables; only inserted/changed results are materialized from the adapter.\n     */\n    private void reconcileActiveQueryCards() {''')

# Universal timestamp: cache formatting/style state and lower the non-urgent stats worker priority.
timestamp = "app/src/main/java/fr/neamar/kiss/ui/UniversalHistoryTimestamp.java"
replace_once(timestamp,
    'import android.text.format.DateFormat;\nimport android.view.View;',
    'import android.text.format.DateFormat;\nimport android.util.LruCache;\nimport android.view.View;')
replace_once(timestamp,
    'import java.util.Date;\nimport java.util.Map;',
    'import java.util.Date;\nimport java.util.Map;\nimport java.util.TimeZone;\nimport java.util.WeakHashMap;')
replace_once(timestamp,
    '''    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();\n    private static final ConcurrentHashMap<String, Long> FIRST_SEEN = new ConcurrentHashMap<>();''',
    '''    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {\n        Thread thread = new Thread(r, "smart-s-history-stats");\n        thread.setPriority(Thread.MIN_PRIORITY);\n        return thread;\n    });\n    private static final ConcurrentHashMap<String, Long> FIRST_SEEN = new ConcurrentHashMap<>();\n    private static final LruCache<String, CharSequence> FORMATTED_CACHE = new LruCache<>(512);\n    private static final WeakHashMap<TextView, Boolean> STYLED_VIEWS = new WeakHashMap<>();''')
replace_once(timestamp,
    '''        timestampView.setVisibility(View.VISIBLE);\n        timestampView.setText(formatTimestamp(context, resolveTimestamp(pojo, stats), stats));\n        SmartTextAppearance.applyHistoryMetadata(timestampView);\n\n        ensureStatsLoaded(row, result, context);''',
    '''        timestampView.setVisibility(View.VISIBLE);\n        long resolvedTimestamp = resolveTimestamp(pojo, stats);\n        timestampView.setText(formatTimestampCached(context, pojo, resolvedTimestamp, stats));\n        if (!STYLED_VIEWS.containsKey(timestampView)) {\n            SmartTextAppearance.applyHistoryMetadata(timestampView);\n            STYLED_VIEWS.put(timestampView, Boolean.TRUE);\n        }\n\n        ensureStatsLoaded(row, result, context);''')
replace_once(timestamp,
    '''    private static CharSequence formatTimestamp(Context context, long timestamp,\n                                                LaunchStatsProvider.LaunchStats stats) {''',
    '''    private static CharSequence formatTimestampCached(\n            Context context, Pojo pojo, long timestamp, LaunchStatsProvider.LaunchStats stats) {\n        int interactionsToday = stats == null ? 0 : Math.max(0, stats.launchesToday);\n        String historyId = pojo.getHistoryId();\n        if (TextUtils.isEmpty(historyId)) historyId = pojo.id;\n        String locale = context.getResources().getConfiguration().locale.toLanguageTag();\n        String key = historyId + '|' + timestamp + '|' + interactionsToday + '|'\n                + DateFormat.is24HourFormat(context) + '|' + locale + '|'\n                + TimeZone.getDefault().getID();\n        synchronized (FORMATTED_CACHE) {\n            CharSequence cached = FORMATTED_CACHE.get(key);\n            if (cached != null) return cached;\n        }\n        CharSequence formatted = formatTimestamp(context, timestamp, interactionsToday);\n        synchronized (FORMATTED_CACHE) {\n            FORMATTED_CACHE.put(key, formatted);\n        }\n        return formatted;\n    }\n\n    private static CharSequence formatTimestamp(Context context, long timestamp,\n                                                int interactionsToday) {''')
replace_once(timestamp,
    '        int interactionsToday = stats == null ? 0 : Math.max(0, stats.launchesToday);\n        return new StringBuilder()',
    '        return new StringBuilder()')

print("3.30.77 guarded source patch applied successfully")
