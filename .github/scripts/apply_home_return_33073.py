from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one baseline match, found {count}")
    return text.replace(old, new, 1)


root = Path('.')

# Version bump from the exact verified 3.30.72 baseline.
build = root / 'app/build.gradle'
text = build.read_text()
old = '''        // Smart S Launcher 3.30.72 - Activity Launcher insets, live search and on-demand privileged enabling
        versionCode 500
        versionName "3.30.72"
'''
new = '''        // Smart S Launcher 3.30.73 - immediate Home history restoration after search launches
        versionCode 501
        versionName "3.30.73"
'''
text = replace_once(text, old, new, 'version')
build.write_text(text)

# Route the verified search-launch return through the dedicated immediate History restoration path.
main = root / 'app/src/main/java/fr/neamar/kiss/MainActivity.java'
text = main.read_text()
old = '''        if (resetDefaultHistoryAfterSearchLaunch) {
            forwarderManager.prepareDefaultHistoryAfterSearchLaunch();
            cancelSearch();
            if (!TextUtils.isEmpty(searchEditText.getText())) {
                clearSearchText();
            }
            // Emptying the EditText only changes UI chrome; it does not publish HISTORY. Always
            // replace the old QUERY adapter with the actual default history result set.
            showHistory();
            displayClearOnInput();
            hideKeyboard();
'''
new = '''        if (resetDefaultHistoryAfterSearchLaunch) {
            forwarderManager.prepareDefaultHistoryAfterSearchLaunch();
            if (!TextUtils.isEmpty(searchEditText.getText())) {
                clearSearchText();
            }
            // A cancelled provider query can still be unwinding on the serialized search worker.
            // Do not leave its QUERY adapter visible while the authoritative History search waits
            // for that worker. Restore a safe cached/recent History snapshot immediately, refresh
            // that snapshot on the independent history-seed worker, then let the full History
            // search run on the normal serialized lane.
            SearchHandler.getInstance().restoreHomeHistory(this);
            displayClearOnInput();
            hideKeyboard();
'''
text = replace_once(text, old, new, 'main-home-reset')
main.write_text(text)

# Harden SearchHandler's Home-return publication without introducing concurrent full provider scans.
handler = root / 'app/src/main/java/fr/neamar/kiss/searcher/SearchHandler.java'
text = handler.read_text()
text = replace_once(
    text,
    'import fr.neamar.kiss.pojo.Pojo;\n',
    'import fr.neamar.kiss.pojo.AppPojo;\nimport fr.neamar.kiss.pojo.Pojo;\n',
    'searchhandler-app-import')

old = '''    public void search(@NonNull Searcher.Type type, @NonNull MainActivity activity,
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
'''
new = '''    public void search(@NonNull Searcher.Type type, @NonNull MainActivity activity,
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

        publishHomeHistoryPreview(activity, generation, historyQuerySeed);
        refreshHistorySeedAndPublish(activity, generation);

        // Keep the complete HistorySearcher on the normal serialized worker. This avoids racing a
        // still-unwinding provider QUERY over shared provider-owned Pojo instances/relevance fields.
        startSearch(Searcher.Type.HISTORY, activity, null, false, generation);
    }
'''
text = replace_once(text, old, new, 'searchhandler-search-method')

old = '''    /** Preload the saved-history search set without delaying the visible History search. */
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
'''
new = '''    /** Preload the saved-history search set without delaying the visible History search. */
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

    /** Refresh the lightweight History seed independently and publish it only while still current. */
    private void refreshHistorySeedAndPublish(@NonNull MainActivity activity, long generation) {
        final WeakReference<MainActivity> activityRef = new WeakReference<>(activity);
        historySeedExecutor.execute(() -> {
            if (generation != searchGeneration.get()) return;
            MainActivity currentActivity = activityRef.get();
            if (currentActivity == null) return;

            List<Pojo> seed = loadHistorySeed(currentActivity);
            if (generation != searchGeneration.get()) return;
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
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        int maxResults = getConfiguredHistoryResultCount(prefs);
        if (maxResults <= 0) return Collections.emptyList();

        DataHandler dataHandler = fr.neamar.kiss.KissApplication.getApplication(activity).getDataHandler();
        Set<String> excluded = new HashSet<>(dataHandler.getExcludedFromHistory());
        if (prefs.getBoolean("exclude-favorites-history", false)) {
            for (Pojo favorite : dataHandler.getFavorites()) {
                if (favorite == null) continue;
                excluded.add(favorite.id);
                String historyId = favorite.getHistoryId();
                if (historyId != null) excluded.add(historyId);
            }
        }
        boolean keepFrozenHistory = prefs.getBoolean("smart-keep-frozen-history", true);

        return HomeHistoryWindow.select(
                seed,
                RecentLaunchTracker.getMostRecent(),
                maxResults,
                pojo -> pojo == null ? null : pojo.getHistoryId(),
                pojo -> {
                    if (pojo == null) return false;
                    String historyId = pojo.getHistoryId();
                    if (historyId == null || excluded.contains(historyId) || excluded.contains(pojo.id)) {
                        return false;
                    }
                    return keepFrozenHistory
                            || !(pojo instanceof AppPojo)
                            || !((AppPojo) pojo).isDisabled();
                });
    }
'''
text = replace_once(text, old, new, 'searchhandler-history-seed')

old = '''    private int getConfiguredSearchResultCount(SharedPreferences prefs) {
        try {
            String legacyValue = prefs.getString("number-of-display-elements",
                    String.valueOf(Searcher.DEFAULT_MAX_RESULTS));
            return Math.max(0, Double.valueOf(prefs.getString("number-of-search-results",
                    legacyValue)).intValue());
        } catch (NumberFormatException | ClassCastException e) {
            return Searcher.DEFAULT_MAX_RESULTS;
        }
    }
'''
new = '''    private int getConfiguredSearchResultCount(SharedPreferences prefs) {
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
'''
text = replace_once(text, old, new, 'searchhandler-history-count')
handler.write_text(text)

# Pure ordering helper: no Android dependencies, so the immediate snapshot contract is unit-testable.
window = root / 'app/src/main/java/fr/neamar/kiss/searcher/HomeHistoryWindow.java'
if window.exists():
    raise SystemExit('HomeHistoryWindow.java unexpectedly already exists')
window.write_text('''package fr.neamar.kiss.searcher;\n\nimport java.util.ArrayList;\nimport java.util.Collections;\nimport java.util.HashSet;\nimport java.util.List;\nimport java.util.Set;\nimport java.util.function.Function;\nimport java.util.function.Predicate;\n\n/** Select a bounded oldest-to-newest Home History snapshot from cached rows plus the latest launch. */\nfinal class HomeHistoryWindow {\n    private HomeHistoryWindow() { }\n\n    static <T> List<T> select(List<T> oldestToNewest, T mostRecent, int maxItems,\n                              Function<T, String> idProvider, Predicate<T> eligible) {\n        if (maxItems <= 0) return Collections.emptyList();\n\n        List<T> newestFirst = new ArrayList<>(maxItems);\n        Set<String> seen = new HashSet<>();\n        addIfEligible(newestFirst, seen, mostRecent, maxItems, idProvider, eligible);\n\n        if (oldestToNewest != null) {\n            for (int i = oldestToNewest.size() - 1; i >= 0 && newestFirst.size() < maxItems; i--) {\n                addIfEligible(newestFirst, seen, oldestToNewest.get(i), maxItems, idProvider, eligible);\n            }\n        }\n\n        Collections.reverse(newestFirst);\n        return newestFirst;\n    }\n\n    private static <T> void addIfEligible(List<T> newestFirst, Set<String> seen, T item,\n                                          int maxItems, Function<T, String> idProvider,\n                                          Predicate<T> eligible) {\n        if (item == null || newestFirst.size() >= maxItems || !eligible.test(item)) return;\n        String id = idProvider.apply(item);\n        if (id == null || id.isEmpty() || !seen.add(id)) return;\n        newestFirst.add(item);\n    }\n}\n''')

# Regression tests for newest placement, deduplication, bounding and filtering.
test = root / 'app/src/test/java/fr/neamar/kiss/searcher/HomeHistoryWindowTest.java'
if test.exists():
    raise SystemExit('HomeHistoryWindowTest.java unexpectedly already exists')
test.write_text('''package fr.neamar.kiss.searcher;\n\nimport static org.hamcrest.MatcherAssert.assertThat;\nimport static org.hamcrest.Matchers.contains;\nimport static org.hamcrest.Matchers.empty;\n\nimport java.util.Arrays;\nimport java.util.Collections;\nimport java.util.List;\nimport java.util.function.Function;\n\nimport org.junit.jupiter.api.Test;\n\nclass HomeHistoryWindowTest {\n    @Test void cachedRecentItemMovesToNewestWithoutDuplication() {\n        List<String> result = HomeHistoryWindow.select(\n                Arrays.asList("A", "B", "C"), "B", 3, Function.identity(), value -> true);\n        assertThat(result, contains("A", "C", "B"));\n    }\n\n    @Test void newRecentItemDropsOnlyTheOldestWhenWindowIsFull() {\n        List<String> result = HomeHistoryWindow.select(\n                Arrays.asList("A", "B", "C"), "D", 3, Function.identity(), value -> true);\n        assertThat(result, contains("B", "C", "D"));\n    }\n\n    @Test void eligibilityAndZeroLimitAreHonored() {\n        List<String> filtered = HomeHistoryWindow.select(\n                Arrays.asList("A", "B", "C"), "D", 3, Function.identity(), value -> !"C".equals(value));\n        assertThat(filtered, contains("A", "B", "D"));\n        assertThat(HomeHistoryWindow.select(\n                Collections.singletonList("A"), "B", 0, Function.identity(), value -> true), empty());\n    }\n}\n''')

print('3.30.73 Home-return patch applied with exact 3.30.72 source assertions')
