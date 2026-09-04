package fr.neamar.kiss.searcher;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.utils.fuzzy.SmartMatcher;

public class SearchHandler {

    private static final long QUERY_DEBOUNCE_MS = 16L;
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
    private Runnable pendingQuery;

    private SearchHandler() {
    }

    /** Last search type, needed for refresh. */
    private Searcher.Type lastSearchType;

    /** Last search query, needed for refresh. */
    private String lastSearchQuery;

    /** Running search task. */
    private Searcher runningSearch;

    /** Last successfully rendered history rows, reused as the zero-DB first query stage. */
    private List<Pojo> historyQuerySeed = Collections.emptyList();

    /**
     * Create search task and execute. Rapid query typing is debounced by one display-frame-sized
     * interval so obsolete searches do not repeatedly rebuild complex history/card views without
     * making the first useful result feel delayed.
     */
    public void search(@NonNull Searcher.Type type, @NonNull MainActivity activity,
                       String query, boolean isRefresh) {
        cancelPendingQuery();
        cancelRunningSearch();

        if (type == Searcher.Type.QUERY && !isRefresh) {
            final String scheduledQuery = query;
            final WeakReference<MainActivity> activityRef = new WeakReference<>(activity);
            pendingQuery = () -> {
                pendingQuery = null;
                MainActivity currentActivity = activityRef.get();
                if (currentActivity != null) {
                    startSearch(type, currentActivity, scheduledQuery, false);
                }
            };
            mainHandler.postDelayed(pendingQuery, QUERY_DEBOUNCE_MS);
            return;
        }

        startSearch(type, activity, query, isRefresh);
    }

    private void startSearch(@NonNull Searcher.Type type, @NonNull MainActivity activity,
                             String query, boolean isRefresh) {
        // SmartMatcher caches only immutable preparation for this one search generation. Starting
        // a new operation invalidates it so repeated identical text still observes changed prefs.
        SmartMatcher.beginSearch();
        final Searcher.Type startedType = type;
        final WeakReference<MainActivity> activityRef = new WeakReference<>(activity);
        runningSearch = createSearcher(type, activity, query, isRefresh);
        runningSearch.setSearchDoneCallback((searcher, isCancelled) -> {
            MainActivity currentActivity = activityRef.get();
            if (!isCancelled && startedType == Searcher.Type.HISTORY
                    && currentActivity != null && currentActivity.adapter != null) {
                historyQuerySeed = currentActivity.adapter.snapshotPojos();
            }
            if (runningSearch == searcher) resetRunningSearch();
        });
        // A prior generation may have been cancelled while still queued. Remove its FutureTask
        // before scheduling this generation so it cannot retain stale search/UI state.
        Searcher.purgeCancelledSearches();
        runningSearch.executeOnExecutor(Searcher.SEARCH_THREAD);
    }

    /** Cancel last search if still running. */
    public void cancelSearch() {
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
