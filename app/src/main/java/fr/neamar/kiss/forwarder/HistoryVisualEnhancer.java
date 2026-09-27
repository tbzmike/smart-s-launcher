package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextView;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.db.AppUsageTodayStore;
import fr.neamar.kiss.db.LaunchStatsProvider;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.SmartTextAppearance;
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
        if (!UniversalHistoryTimestamp.isHistorySurface(activity)) {
            generation++;
            refreshPending = false;
            if (inFlight != null) inFlight.cancel(true);
            inFlight = null;
            return;
        }

        // Scrolling itself does not make usage/launch statistics stale. Older code cancelled the
        // current load and started another DB/UsageStats pass after every fling, creating repeated
        // background work. Keep any in-flight load and only rebind the final visible rows at idle.
        refreshPending = true;
        historyDisplayForwarder.runWhenScrollIdle(refreshAtIdle);
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
            UniversalHistoryTimestamp.updateStats(cachedStats);
            applyStatsToVisibleRows(cachedStats, cachedUsage);
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
        UniversalHistoryTimestamp.updateStats(cachedStats);
        applyStatsToVisibleRows(cachedStats, cachedUsage);
        if (refreshPending) requestRefresh();
    }

    private void applyStatsToVisibleRows(Map<String, LaunchStatsProvider.LaunchStats> stats,
                                         AppUsageTodayStore.Snapshot usage) {
        if (!UniversalHistoryTimestamp.isHistorySurface(activity)
                || activity.list == null || activity.adapter == null) {
            return;
        }
        int first = activity.list.getFirstVisiblePosition();
        java.text.DateFormat timeFormat = DateFormat.getTimeFormat(activity);

        for (int childIndex = 0; childIndex < activity.list.getChildCount(); childIndex++) {
            int position = first + childIndex;
            if (position < 0 || position >= activity.adapter.getCount()) continue;
            View row = activity.list.getChildAt(childIndex);
            row.setBackgroundColor(Color.TRANSPARENT);
            row.setElevation(0f);
            row.setTranslationZ(0f);

            Result<?> result = activity.adapter.getItem(position);
            if (result == null || result.getPojo() == null) continue;

            // UniversalHistoryTimestamp and the enrichment pass must share the same layout-owned
            // metadata TextView. This prevents two independent metadata views from competing for
            // row height/content while still keeping the richer usage information.
            UniversalHistoryTimestamp.bind(row, result, activity);
            TextView metadataView = findMetadataView(row);
            if (metadataView == null) continue;

            LaunchStatsProvider.LaunchStats launchStats =
                    stats.get(result.getPojo().getHistoryId());
            StringBuilder metadata = new StringBuilder();
            CharSequence baseMetadata = metadataView.getText();
            if (!TextUtils.isEmpty(baseMetadata)) metadata.append(baseMetadata);

            // For app rows the universal timestamp already represents last launch time. For
            // notification/communication rows it represents the event time, so last-opened remains
            // useful supplemental information rather than a duplicate timestamp.
            if (!(result.getPojo() instanceof AppPojo)
                    && launchStats != null && launchStats.lastLaunchTime > 0L) {
                appendMetadata(metadata, "Last opened "
                        + timeFormat.format(new java.util.Date(launchStats.lastLaunchTime)));
            }
            if (result.getPojo() instanceof AppPojo && usage != null && usage.available) {
                Long foregroundMs = usage.foregroundMsByPackage.get(
                        ((AppPojo) result.getPojo()).packageName);
                appendMetadata(metadata, "Used today "
                        + formatDuration(foregroundMs == null ? 0L : foregroundMs));
            }

            metadataView.setText(metadata);
            metadataView.setVisibility(metadata.length() == 0 ? View.GONE : View.VISIBLE);
            if (metadata.length() > 0) {
                SmartTextAppearance.applyHistoryMetadata(metadataView);
            }
        }
    }

    private TextView findMetadataView(View row) {
        View candidate = row.findViewById(R.id.item_history_meta);
        return candidate instanceof TextView ? (TextView) candidate : null;
    }

    private void appendMetadata(StringBuilder builder, String value) {
        if (TextUtils.isEmpty(value)) return;
        if (builder.length() > 0) builder.append(" • ");
        builder.append(value);
    }

    private String formatDuration(long durationMs) {
        long totalMinutes = Math.max(0L, durationMs) / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return hours > 0L ? hours + "h " + minutes + "m" : minutes + "m";
    }
}
