package fr.neamar.kiss.forwarder;

import android.os.SystemClock;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.AppUsageTodayStore;
import fr.neamar.kiss.db.LaunchStatsProvider;
import fr.neamar.kiss.ui.UniversalHistoryTimestamp;

/** Loads native-list/wheel history metadata only while the active viewport is idle. */
final class HistoryVisualEnhancer {
    private static final long MIN_RELOAD_INTERVAL_MS = 15_000L;

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "smart-s-idle-history-metadata");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final Runnable refreshAtIdle = this::refreshNow;
    private final Runnable scrollStarted = this::onScrollStarted;

    private Future<?> inFlight;
    private boolean refreshPending;
    private boolean listenerRegistered;
    private boolean destroyed;
    private boolean forceReload = true;
    private long generation;
    private long lastLoadUptime;
    private Map<String, LaunchStatsProvider.LaunchStats> cachedStats = java.util.Collections.emptyMap();
    private AppUsageTodayStore.Snapshot cachedUsage;

    HistoryVisualEnhancer(MainActivity activity,
                          HistoryDisplayForwarder historyDisplayForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
    }

    void onResume() {
        ensureScrollListener();
        requestRefresh();
    }

    void onDataSetChanged() {
        ensureScrollListener();
        forceReload = true;
        requestRefresh();
    }

    void onDestroy() {
        destroyed = true;
        generation++;
        if (listenerRegistered) {
            historyDisplayForwarder.removeScrollStartedListener(scrollStarted);
            listenerRegistered = false;
        }
        if (inFlight != null) inFlight.cancel(true);
        inFlight = null;
        executor.shutdownNow();
    }

    private void ensureScrollListener() {
        if (listenerRegistered || destroyed) return;
        historyDisplayForwarder.addScrollStartedListener(scrollStarted);
        listenerRegistered = true;
    }

    private void requestRefresh() {
        if (destroyed || !UniversalHistoryTimestamp.isHistorySurface(activity)) {
            refreshPending = false;
            return;
        }
        refreshPending = true;
        historyDisplayForwarder.runWhenScrollIdle(refreshAtIdle);
    }

    private void onScrollStarted() {
        if (destroyed) return;

        // Scrolling is render-only. Stop metadata/database/UsageStats work immediately. Do not
        // create a new refresh merely because the user scrolled.
        generation++;
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
            // This refresh was already requested for real data/lifecycle reasons, so remember it.
            refreshPending = true;
        }

        if (!UniversalHistoryTimestamp.isHistorySurface(activity)) {
            refreshPending = false;
            return;
        }

        if (refreshPending) {
            historyDisplayForwarder.runWhenScrollIdle(refreshAtIdle);
        }
    }

    private void refreshNow() {
        if (destroyed || !UniversalHistoryTimestamp.isHistorySurface(activity)) {
            refreshPending = false;
            return;
        }
        if (historyDisplayForwarder.isScrollInProgress()) {
            requestRefresh();
            return;
        }
        if (inFlight != null && !inFlight.isDone()) {
            refreshPending = true;
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (!forceReload && !cachedStats.isEmpty()
                && now - lastLoadUptime < MIN_RELOAD_INTERVAL_MS) {
            refreshPending = false;
            UniversalHistoryTimestamp.updateEnrichment(cachedStats, cachedUsage);
            return;
        }

        refreshPending = false;
        final long taskGeneration = ++generation;
        inFlight = executor.submit(() -> {
            Map<String, LaunchStatsProvider.LaunchStats> stats =
                    LaunchStatsProvider.loadAll(activity.getApplicationContext());
            if (Thread.currentThread().isInterrupted()) return;
            AppUsageTodayStore.Snapshot usage =
                    AppUsageTodayStore.getToday(activity.getApplicationContext());
            if (Thread.currentThread().isInterrupted()) return;
            activity.runOnUiThread(() -> finishRefresh(taskGeneration, stats, usage));
        });
    }

    private void finishRefresh(long taskGeneration,
                               Map<String, LaunchStatsProvider.LaunchStats> stats,
                               AppUsageTodayStore.Snapshot usage) {
        if (destroyed || taskGeneration != generation) return;
        inFlight = null;
        if (!UniversalHistoryTimestamp.isHistorySurface(activity)) {
            // A History load can finish after the user has started typing. Never decorate the
            // current QUERY tree with data loaded for the previous History surface.
            refreshPending = false;
            return;
        }
        if (historyDisplayForwarder.isScrollInProgress()) {
            requestRefresh();
            return;
        }

        cachedStats = stats == null ? java.util.Collections.emptyMap() : stats;
        cachedUsage = usage;
        lastLoadUptime = SystemClock.uptimeMillis();
        forceReload = false;
        // Update only the model/cache. Rewriting visible TextViews here caused the exact
        // after-scroll text jump seen in the supplied video.
        UniversalHistoryTimestamp.updateEnrichment(cachedStats, cachedUsage);
        if (refreshPending) requestRefresh();
    }

}
