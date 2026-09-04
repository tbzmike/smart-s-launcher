package fr.neamar.kiss.searcher;


import android.content.Context;
import android.os.AsyncTask;

import androidx.annotation.CallSuper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.RelevanceComparator;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.utils.Log;

public abstract class Searcher extends AsyncTask<Void, Result<?>, Void> {

    private static final String TAG = Searcher.class.getSimpleName();

    public enum Type {
        APPLICATION,
        QUERY,
        NULL,
        HISTORY,
        TAGGED,
        UNTAGGED
    }

    @FunctionalInterface
    public interface SearchDoneCallback {
        void execute(Searcher searcher, boolean isCancelled);
    }

    /**
     * Search is serialized, but its pending queue must stay bounded. Rapid typing can cancel
     * searches faster than a long provider pass exits; an unbounded single-thread executor keeps
     * those cancelled AsyncTasks and their referenced state alive until eventually dequeued.
     */
    public static final ThreadPoolExecutor SEARCH_THREAD = new ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),
            runnable -> new Thread(runnable, "smart-s-search"),
            new ThreadPoolExecutor.DiscardOldestPolicy());

    static {
        SEARCH_THREAD.allowCoreThreadTimeOut(true);
    }

    protected static final int DEFAULT_MAX_RESULTS = 50;
    protected final WeakReference<MainActivity> activityWeakReference;
    private final PriorityQueue<Pojo> processedPojos;
    private long start;
    private SearchDoneCallback searchDoneCallback;
    private boolean managingLoader;
    private final boolean isRefresh;
    protected final String query;

    protected Searcher(MainActivity activity, String query, boolean isRefresh) {
        super();
        this.isRefresh = isRefresh;
        this.query = query == null ? null : query.trim();
        this.activityWeakReference = new WeakReference<>(activity);
        this.processedPojos = getPojoProcessor(activity);
    }

    /** Remove cancelled FutureTasks immediately instead of retaining them in the worker queue. */
    public static void purgeCancelledSearches() {
        SEARCH_THREAD.purge();
    }

    PriorityQueue<Pojo> getPojoProcessor(Context context) {
        return new PriorityQueue<>(DEFAULT_MAX_RESULTS, new RelevanceComparator());
    }

    protected int getMaxResultCount() {
        return DEFAULT_MAX_RESULTS;
    }

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

    public final boolean addResult(Pojo pojos) {
        return addResults(Collections.singletonList(pojos));
    }

    public boolean addResults(List<? extends Pojo> pojos) {
        if (isCancelled()) return false;

        boolean changed = false;
        int maxResults = Math.max(0, getMaxResultCount());
        for (Pojo pojo : pojos) {
            if (pojo == null) continue;
            changed |= this.processedPojos.offer(pojo);
            if (this.processedPojos.size() > maxResults) this.processedPojos.poll();
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
        if (activity == null) return;
        activity.displayLoader(true);
    }

    private void hideActivityLoader(MainActivity activity) {
        activity.displayLoader(!KissApplication.getApplication(activity).getDataHandler().isAllProvidersLoaded());
    }

    @Override
    protected void onPostExecute(Void param) {
        if (isCancelled()) return;

        MainActivity activity = activityWeakReference.get();
        if (activity == null) return;

        if (this.processedPojos.isEmpty()) {
            activity.adapter.clear();
        } else {
            PriorityQueue<Pojo> queue = this.processedPojos;
            int maxResults = getMaxResultCount();
            while (queue.size() > maxResults) queue.poll();
            List<Pojo> pojos = new ArrayList<>(queue.size());
            while (queue.peek() != null) {
                Pojo pojo = queue.poll();
                if (pojo != null) pojos.add(pojo);
            }
            activity.adapter.updateWithPojos(activity, pojos, isRefresh, query);
        }

        searchDone(false);
        if (managingLoader) hideActivityLoader(activity);

        long time = System.currentTimeMillis() - start;
        Log.d(TAG, "Time to run query `" + query + "` on " + getClass().getSimpleName()
                + " to completion: " + time + "ms (isRefresh=" + isRefresh + ")");
    }

    private void searchDone(boolean isCancelled) {
        if (searchDoneCallback != null) searchDoneCallback.execute(this, isCancelled);
        searchDoneCallback = null;
    }

    @Override
    protected void onCancelled(Void unused) {
        searchDone(true);
        MainActivity activity = activityWeakReference.get();
        if (activity == null) return;
        if (managingLoader) hideActivityLoader(activity);
    }

    public void setSearchDoneCallback(SearchDoneCallback searchDoneCallback) {
        this.searchDoneCallback = searchDoneCallback;
    }
}
