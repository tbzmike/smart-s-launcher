package fr.neamar.kiss.forwarder;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.SystemClock;
import android.view.View;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.AppUsageTodayStore;
import fr.neamar.kiss.db.LaunchStatsProvider;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.UniversalHistoryTimestamp;
import fr.neamar.kiss.utils.LauncherScrollWorkGate;

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
    private long lastStatsGeneration = -1L;
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

    void onScrollIdle() {
        // Scrolling may have recycled native rows while metadata decoration was intentionally
        // frozen. Re-apply only cached metadata to the now-visible rows; no database/UsageStats
        // work is started by the act of scrolling.
        applyToVisibleNativeRows();
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
        long requestedGeneration = UniversalHistoryTimestamp.statsGeneration();
        boolean explicitLaunchChanged = requestedGeneration != lastStatsGeneration;
        if (!forceReload && !explicitLaunchChanged && hasLoadedSnapshot
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

                AppUsageTodayStore.Snapshot usage = null;
                // Full History metadata includes today's foreground duration. Query only from the
                // low-priority idle worker and never begin the UsageStats binder call while the
                // launcher is moving; AppUsageTodayStore also shares a 30-second process cache.
                if (!historyDisplayForwarder.isScrollInProgress()
                        && !LauncherScrollWorkGate.isScrolling()) {
                    usage = AppUsageTodayStore.getToday(activity.getApplicationContext());
                }
                if (signal.isCanceled() || Thread.currentThread().isInterrupted()) return;

                AppUsageTodayStore.Snapshot finalUsage = usage;
                activity.runOnUiThread(() ->
                        finishRefresh(taskGeneration, stats, finalUsage));
            } catch (OperationCanceledException ignored) {
                // Expected when the user starts scrolling.
            }
        });
    }

    /**
     * Refresh only the already-visible native History rows after the idle enrichment snapshot is
     * ready. This gives every visible item its full timestamp/launch/usage line without publishing a
     * new adapter dataset or rebuilding the History tree.
     */
    private void applyToVisibleNativeRows() {
        if (destroyed || activity.list == null || activity.adapter == null
                || historyDisplayForwarder.isScrollInProgress()
                || LauncherScrollWorkGate.isScrolling()) {
            return;
        }
        int first = activity.list.getFirstVisiblePosition();
        int childCount = activity.list.getChildCount();
        for (int i = 0; i < childCount; i++) {
            int position = first + i;
            if (position < 0 || position >= activity.adapter.getCount()) continue;
            View child = activity.list.getChildAt(i);
            Result<?> result = activity.adapter.getItem(position);
            if (child != null && result != null) {
                UniversalHistoryTimestamp.bind(child, result, activity);
            }
        }
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
        lastStatsGeneration = UniversalHistoryTimestamp.statsGeneration();
        forceReload = false;
        hasLoadedSnapshot = true;
        UniversalHistoryTimestamp.updateEnrichment(cachedStats, cachedUsage);
        applyToVisibleNativeRows();
        if (refreshPending) requestRefresh();
    }

}
