package fr.neamar.kiss.searcher;


import android.content.Context;
import android.os.AsyncTask;

import androidx.annotation.CallSuper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.RelevanceComparator;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.utils.Log;

public abstract class Searcher extends AsyncTask<Void, Result<?>, Void> {

    private static final String TAG = Searcher.class.getSimpleName();

    /**
     * Possible types of search
     */
    public enum Type {
        APPLICATION,
        QUERY,
        NULL,
        HISTORY,
        TAGGED,
        UNTAGGED
    }

    /**
     * Callback for when search is done.
     */
    @FunctionalInterface
    public interface SearchDoneCallback {
        /**
         * Execute when search is done.
         *
         * @param isCancelled true if search was cancelled
         */
        void execute(Searcher searcher, boolean isCancelled);
    }

    // define a different thread than the default AsyncTask thread or else we will block everything else that uses AsyncTask while we search
    public static final ExecutorService SEARCH_THREAD = Executors.newSingleThreadExecutor();
    protected static final int DEFAULT_MAX_RESULTS = 50;
    protected final WeakReference<MainActivity> activityWeakReference;
    private final PriorityQueue<Pojo> processedPojos;
    private long start;
    private SearchDoneCallback searchDoneCallback;
    private boolean managingLoader;

    /**
     * Set to true when we are simply refreshing current results (scroll will not be reset)
     * When false, we reset the scroll back to the last item in the list
     */
    private final boolean isRefresh;
    protected final String query;

    protected Searcher(MainActivity activity, String query, boolean isRefresh) {
        super();
        this.isRefresh = isRefresh;
        this.query = query == null ? null : query.trim();
        this.activityWeakReference = new WeakReference<>(activity);
        this.processedPojos = getPojoProcessor(activity);
    }

    PriorityQueue<Pojo> getPojoProcessor(Context context) {
        return new PriorityQueue<>(DEFAULT_MAX_RESULTS, new RelevanceComparator());
    }

    protected int getMaxResultCount() {
        return DEFAULT_MAX_RESULTS;
    }

    /** Publish a preview list without adding it to the final provider result queue. */
    protected final void publishPreviewResults(List<? extends Pojo> previewPojos) {
        if (isCancelled() || previewPojos == null || previewPojos.isEmpty()) return;
        MainActivity activity = activityWeakReference.get();
        if (activity == null) return;

        int maxResults = Math.max(0, getMaxResultCount());
        int from = Math.max(0, previewPojos.size() - maxResults);
        List<Pojo> snapshot = new ArrayList<>(previewPojos.size() - from);
        for (int i = from; i < previewPojos.size(); i++) {
            Pojo pojo = previewPojos.get(i);
            if (pojo != null) snapshot.add(pojo);
        }
        if (snapshot.isEmpty()) return;

        activity.runOnUiThread(() -> {
            if (isCancelled()) return;
            MainActivity currentActivity = activityWeakReference.get();
            if (currentActivity == null) return;
            currentActivity.adapter.updateWithPojos(currentActivity, snapshot, true, query);
        });
    }

    /** Publish a stable snapshot without ending the active search. */
    protected final void publishCurrentResults() {
        if (isCancelled()) return;
        MainActivity activity = activityWeakReference.get();
        if (activity == null) return;

        PriorityQueue<Pojo> copy = new PriorityQueue<>(processedPojos);
        int maxResults = Math.max(0, getMaxResultCount());
        while (copy.size() > maxResults) copy.poll();
        List<Pojo> snapshot = new ArrayList<>(copy.size());
        while (copy.peek() != null) {
            Pojo pojo = copy.poll();
            if (pojo != null) snapshot.add(pojo);
        }
        if (snapshot.isEmpty()) return;

        activity.runOnUiThread(() -> {
            if (isCancelled()) return;
            MainActivity currentActivity = activityWeakReference.get();
            if (currentActivity == null) return;
            currentActivity.adapter.updateWithPojos(currentActivity, snapshot, true, query);
        });
    }

    /**
     * Add single pojo to results.
     * This is called from the background thread by the providers.
     */
    public final boolean addResult(Pojo pojos) {
        return addResults(Collections.singletonList(pojos));
    }

    /**
     * Add one or more pojos to results.
     * This is called from the background thread by the providers.
     *
     * Keep only the best configured number of candidates while the search is running. Previously
     * the queue could grow to every fuzzy/provider match and was trimmed only in onPostExecute().
     * Since RelevanceComparator makes the queue head the weakest result, immediately polling when
     * the limit is exceeded preserves the same final top-N set while substantially reducing queue
     * allocations/comparisons for large app, contact and shortcut indexes.
     */
    public boolean addResults(List<? extends Pojo> pojos) {
        if (isCancelled()) return false;

        boolean changed = false;
        int maxResults = Math.max(0, getMaxResultCount());
        for (Pojo pojo : pojos) {
            if (pojo == null) continue;
            changed |= this.processedPojos.offer(pojo);
            if (this.processedPojos.size() > maxResults) {
                this.processedPojos.poll();
            }
        }
        return changed;
    }

    @CallSuper
    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        start = System.currentTimeMillis();

        MainActivity activity = activityWeakReference.get();
        managingLoader = activity != null
                && !KissApplication.getApplication(activity).getDataHandler().isAllProvidersLoaded();
        if (managingLoader) displayActivityLoader();
    }

    protected void displayActivityLoader() {
        MainActivity activity = activityWeakReference.get();
        if (activity == null)
            return;

        activity.displayLoader(true);
    }

    private void hideActivityLoader(MainActivity activity) {
        // Loader should still be displayed until all the providers have finished loading
        activity.displayLoader(!KissApplication.getApplication(activity).getDataHandler().isAllProvidersLoaded());
    }

    @Override
    protected void onPostExecute(Void param) {
        if (isCancelled()) {
            return;
        }

        MainActivity activity = activityWeakReference.get();
        if (activity == null)
            return;

        if (this.processedPojos.isEmpty()) {
            activity.adapter.clear();
        } else {
            PriorityQueue<Pojo> queue = this.processedPojos;
            int maxResults = getMaxResultCount();
            while (queue.size() > maxResults) {
                queue.poll();
            }
            List<Pojo> pojos = new ArrayList<>(queue.size());
            while (queue.peek() != null) {
                Pojo pojo = queue.poll();
                if (pojo != null) {
                    pojos.add(pojo);
                }
            }

            activity.adapter.updateWithPojos(activity, pojos, isRefresh, query);
        }

        searchDone(false);

        if (managingLoader) hideActivityLoader(activity);

        long time = System.currentTimeMillis() - start;
        Log.d(TAG, "Time to run query `" + query + "` on " + getClass().getSimpleName() + " to completion: " + time + "ms (isRefresh=" + isRefresh + ")");
    }

    private void searchDone(boolean isCancelled) {
        if (searchDoneCallback != null) {
            searchDoneCallback.execute(this, isCancelled);
        }
    }

    @Override
    protected void onCancelled(Void unused) {
        searchDone(true);

        MainActivity activity = activityWeakReference.get();
        if (activity == null)
            return;

        if (managingLoader) hideActivityLoader(activity);
    }

    public void setSearchDoneCallback(SearchDoneCallback searchDoneCallback) {
        this.searchDoneCallback = searchDoneCallback;
    }
}
