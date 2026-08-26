package fr.neamar.kiss.ui;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.LaunchStatsProvider;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.result.Result;

/**
 * Guarantees that every record rendered on the launcher history/home list has a dedicated
 * timestamp line underneath its normal content. The same row also shows the number of launcher
 * interactions recorded for that exact history item during the current local calendar day.
 */
public final class UniversalHistoryTimestamp {
    private static final String VIEW_TAG = "smart_s_universal_history_timestamp";
    private static final long STATS_REFRESH_MS = 2_000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String, Long> FIRST_SEEN = new ConcurrentHashMap<>();
    private static volatile Map<String, LaunchStatsProvider.LaunchStats> launchStats;
    private static volatile long statsLoadedAt;
    private static volatile boolean loadInFlight;

    private UniversalHistoryTimestamp() {}

    public static void bind(@NonNull View row, @NonNull Result<?> result, @NonNull Context context) {
        if (!isHistorySurface(context)) {
            TextView existing = findTaggedTimestamp(row);
            if (existing != null) existing.setVisibility(View.GONE);
            return;
        }

        Pojo pojo = result.getPojo();
        if (pojo == null) return;

        TextView timestampView = ensureTimestampView(row, context);
        if (timestampView == null) return;

        LaunchStatsProvider.LaunchStats stats = resolveStats(pojo);
        timestampView.setVisibility(View.VISIBLE);
        timestampView.setText(formatTimestamp(context, resolveTimestamp(pojo, stats), stats));
        SmartTextAppearance.applyHistoryMetadata(timestampView);

        ensureStatsLoaded(row, result, context);
    }

    private static boolean isHistorySurface(Context context) {
        if (!(context instanceof MainActivity)) return false;
        MainActivity activity = (MainActivity) context;
        return activity.searchEditText == null || activity.searchEditText.length() == 0;
    }

    private static LaunchStatsProvider.LaunchStats resolveStats(Pojo pojo) {
        Map<String, LaunchStatsProvider.LaunchStats> snapshot = launchStats;
        String historyId = pojo.getHistoryId();
        if (snapshot == null || TextUtils.isEmpty(historyId)) return null;
        return snapshot.get(historyId);
    }

    private static long resolveTimestamp(Pojo pojo, LaunchStatsProvider.LaunchStats stats) {
        if (pojo instanceof NotificationPojo) {
            long posted = ((NotificationPojo) pojo).postTime;
            if (posted > 0L) return posted;
        }
        if (pojo instanceof CommunicationPojo) {
            long eventTime = ((CommunicationPojo) pojo).timestamp;
            if (eventTime > 0L) return eventTime;
        }

        if (stats != null && stats.lastLaunchTime > 0L) return stats.lastLaunchTime;

        String historyId = pojo.getHistoryId();
        String key = TextUtils.isEmpty(historyId)
                ? pojo.getClass().getName() + '@' + System.identityHashCode(pojo)
                : historyId;
        return FIRST_SEEN.computeIfAbsent(key, ignored -> System.currentTimeMillis());
    }

    private static CharSequence formatTimestamp(Context context, long timestamp,
                                                LaunchStatsProvider.LaunchStats stats) {
        Date date = new Date(timestamp);
        java.text.DateFormat dateFormat = DateFormat.getMediumDateFormat(context);
        java.text.DateFormat timeFormat = DateFormat.getTimeFormat(context);
        int interactionsToday = stats == null ? 0 : Math.max(0, stats.launchesToday);
        return new StringBuilder()
                .append(dateFormat.format(date))
                .append("  •  ")
                .append(timeFormat.format(date))
                .append("  •  ")
                .append(interactionsToday)
                .append(interactionsToday == 1 ? " interaction today" : " interactions today");
    }

    private static void ensureStatsLoaded(View row, Result<?> result, Context context) {
        long now = System.currentTimeMillis();
        boolean fresh = launchStats != null && now - statsLoadedAt < STATS_REFRESH_MS;
        if (fresh || loadInFlight) return;
        synchronized (UniversalHistoryTimestamp.class) {
            now = System.currentTimeMillis();
            fresh = launchStats != null && now - statsLoadedAt < STATS_REFRESH_MS;
            if (fresh || loadInFlight) return;
            loadInFlight = true;
        }
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            Map<String, LaunchStatsProvider.LaunchStats> loaded = LaunchStatsProvider.loadAll(appContext);
            launchStats = loaded;
            statsLoadedAt = System.currentTimeMillis();
            loadInFlight = false;
            row.post(() -> {
                if (row.isAttachedToWindow()) bind(row, result, context);
            });
        });
    }

    private static TextView ensureTimestampView(View row, Context context) {
        TextView existing = findTaggedTimestamp(row);
        if (existing != null) return existing;

        LinearLayout container = findBestVerticalTextContainer(row);
        if (container == null) return null;

        TextView timestamp = new TextView(context);
        timestamp.setTag(VIEW_TAG);
        timestamp.setSingleLine(true);
        timestamp.setEllipsize(TextUtils.TruncateAt.END);
        timestamp.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        timestamp.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 2);
        container.addView(timestamp, params);
        return timestamp;
    }

    private static TextView findTaggedTimestamp(View view) {
        if (view instanceof TextView && VIEW_TAG.equals(view.getTag())) return (TextView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = findTaggedTimestamp(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static LinearLayout findBestVerticalTextContainer(View view) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;

        if (group instanceof LinearLayout) {
            LinearLayout linear = (LinearLayout) group;
            if (linear.getOrientation() == LinearLayout.VERTICAL && containsText(linear)) {
                return linear;
            }
        }

        for (int i = 0; i < group.getChildCount(); i++) {
            LinearLayout found = findBestVerticalTextContainer(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean containsText(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) return true;
            if (child instanceof ViewGroup && containsText((ViewGroup) child)) return true;
        }
        return false;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
