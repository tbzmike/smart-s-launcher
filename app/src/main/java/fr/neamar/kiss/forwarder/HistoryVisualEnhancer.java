package fr.neamar.kiss.forwarder;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;
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
    private static final long MIN_RELOAD_INTERVAL_MS = 60_000L;

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
    private CancellationSignal cancellationSignal;
    private boolean refreshPending;
    private boolean listenerRegistered;
    private boolean destroyed;
    private boolean forceReload = true;
    private boolean hasLoadedSnapshot;
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
        // Dataset changes are frequent (launches, notifications, provider publications). They must
        // not force a full GROUP BY history scan every time. Reuse the latest enrichment snapshot
        // and refresh it only when the bounded cache window expires.
        requestRefresh();
    }

    void onDestroy() {
        destroyed = true;
        generation++;
        if (listenerRegistered) {
            historyDisplayForwarder.removeScrollStartedListener(scrollStarted);
            listenerRegistered = false;
        }
        CancellationSignal signal = cancellationSignal;
        if (signal != null) signal.cancel();
        cancellationSignal = null;
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

        // Scrolling is render-only. Cancel any metadata/database/UsageStats work immediately and
        // drop it. The act of scrolling must never create deferred work that fires when motion stops.
        // A real resume or dataset change can request fresh metadata later.
        generation++;
        refreshPending = false;
        historyDisplayForwarder.cancelWhenScrollIdle(refreshAtIdle);
        CancellationSignal signal = cancellationSignal;
        if (signal != null) signal.cancel();
        cancellationSignal = null;
        if (inFlight != null) inFlight.cancel(true);
        inFlight = null;
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
        if (!forceReload && hasLoadedSnapshot
                && now - lastLoadUptime < MIN_RELOAD_INTERVAL_MS) {
            refreshPending = false;
            UniversalHistoryTimestamp.updateEnrichment(cachedStats, cachedUsage);
            return;
        }

        refreshPending = false;
        final long taskGeneration = ++generation;
        final CancellationSignal signal = new CancellationSignal();
        cancellationSignal = signal;
        inFlight = executor.submit(() -> {
            try {
                Map<String, LaunchStatsProvider.LaunchStats> stats =
                        LaunchStatsProvider.loadAll(activity.getApplicationContext(), signal);
                if (signal.isCanceled() || Thread.currentThread().isInterrupted()) return;

                // Do not query UsageStats for Vertical List. It is optional decoration and can keep
                // running after a fling begins on some vendor builds. Scroll-critical History now
                // performs only the single cancellation-aware SQLite stats query.
                activity.runOnUiThread(() -> finishRefresh(taskGeneration, stats, null));
            } catch (OperationCanceledException ignored) {
                // Expected when the user starts scrolling.
            }
        });
    }

    private void finishRefresh(long taskGeneration,
                               Map<String, LaunchStatsProvider.LaunchStats> stats,
                               AppUsageTodayStore.Snapshot usage) {
        if (destroyed || taskGeneration != generation) return;
        inFlight = null;
        cancellationSignal = null;
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
        hasLoadedSnapshot = true;
        // Update only the model/cache. Rewriting visible TextViews here caused the exact
        // after-scroll text jump seen in the supplied video.
        UniversalHistoryTimestamp.updateEnrichment(cachedStats, cachedUsage);
        if (refreshPending) requestRefresh();
    }

}
