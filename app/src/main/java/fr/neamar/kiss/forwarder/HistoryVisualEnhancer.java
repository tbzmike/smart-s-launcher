package fr.neamar.kiss.forwarder;

import android.graphics.Color;
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
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.SmartTextAppearance;
import fr.neamar.kiss.ui.UniversalHistoryTimestamp;

/** Loads native-list/wheel history metadata only while the active viewport is idle. */
final class HistoryVisualEnhancer {
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
    private long generation;

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
        if (destroyed) return;
        refreshPending = true;
        historyDisplayForwarder.runWhenScrollIdle(refreshAtIdle);
    }

    private void onScrollStarted() {
        if (destroyed) return;
        generation++;
        refreshPending = true;
        if (inFlight != null) inFlight.cancel(true);
        inFlight = null;
        historyDisplayForwarder.runWhenScrollIdle(refreshAtIdle);
    }

    private void refreshNow() {
        if (destroyed) return;
        if (historyDisplayForwarder.isScrollInProgress()) {
            requestRefresh();
            return;
        }
        if (inFlight != null && !inFlight.isDone()) {
            refreshPending = true;
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
        if (historyDisplayForwarder.isScrollInProgress()) {
            requestRefresh();
            return;
        }

        UniversalHistoryTimestamp.updateStats(stats);
        applyStatsToVisibleRows(stats, usage);
        historyDisplayForwarder.onHistoryMetadataLoaded();
        if (refreshPending) requestRefresh();
    }

    private void applyStatsToVisibleRows(Map<String, LaunchStatsProvider.LaunchStats> stats,
                                         AppUsageTodayStore.Snapshot usage) {
        if (activity.list == null || activity.adapter == null) return;
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
            UniversalHistoryTimestamp.bind(row, result, activity);

            TextView metadataView = findMetadataView(row);
            if (metadataView == null) continue;
            LaunchStatsProvider.LaunchStats launchStats =
                    stats.get(result.getPojo().getHistoryId());
            StringBuilder metadata = new StringBuilder();
            if (result.getPojo() instanceof NotificationPojo) {
                long postTime = ((NotificationPojo) result.getPojo()).postTime;
                if (postTime > 0L) appendMetadata(metadata,
                        "Posted " + timeFormat.format(new java.util.Date(postTime)));
            } else if (result.getPojo() instanceof CommunicationPojo) {
                long eventTime = ((CommunicationPojo) result.getPojo()).timestamp;
                if (eventTime > 0L) appendMetadata(metadata,
                        "Item time " + timeFormat.format(new java.util.Date(eventTime)));
            }
            if (launchStats != null && launchStats.lastLaunchTime > 0L) {
                appendMetadata(metadata, "Last opened "
                        + timeFormat.format(new java.util.Date(launchStats.lastLaunchTime)));
            }
            if (result.getPojo() instanceof AppPojo && usage != null && usage.available) {
                Long foregroundMs = usage.foregroundMsByPackage.get(
                        ((AppPojo) result.getPojo()).packageName);
                appendMetadata(metadata, "Used today "
                        + formatDuration(foregroundMs == null ? 0L : foregroundMs));
            }
            int opensToday = launchStats == null ? 0 : launchStats.launchesToday;
            appendMetadata(metadata, opensToday
                    + (opensToday == 1 ? " open today" : " opens today"));

            metadataView.setText(metadata);
            metadataView.setVisibility(metadata.length() == 0 ? View.GONE : View.VISIBLE);
            if (metadata.length() > 0) {
                SmartTextAppearance.applyHistoryMetadata(metadataView);
                configureMarquee(metadataView);
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

    private void configureMarquee(TextView text) {
        text.setSingleLine(true);
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        text.setMarqueeRepeatLimit(-1);
        text.setHorizontallyScrolling(true);
        text.setHorizontalFadingEdgeEnabled(true);
        text.setSelected(true);
        text.setFocusable(false);
        text.setFocusableInTouchMode(false);
    }
}
