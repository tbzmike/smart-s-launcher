package fr.neamar.kiss.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
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
    private static final long STATS_REFRESH_MS = 30_000L;
    private static final int MAX_FIRST_SEEN_ENTRIES = 512;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<String, Long> FIRST_SEEN = new ConcurrentHashMap<>();

    /**
     * Rows are ListView-owned and recyclable. Keep them only as weak keys and always replace the
     * binding during getView(), so an asynchronous stats refresh can never write an older result
     * into a row that has since been rebound to another item.
     */
    private static final WeakHashMap<View, BoundRow> BOUND_ROWS = new WeakHashMap<>();

    private static volatile Map<String, LaunchStatsProvider.LaunchStats> launchStats;
    private static volatile long statsLoadedAt;
    private static volatile boolean loadInFlight;

    private UniversalHistoryTimestamp() {}

    public static void bind(@NonNull View row, @NonNull Result<?> result, @NonNull Context context) {
        if (!isHistorySurface(context)) {
            BOUND_ROWS.remove(row);
            TextView existing = findTimestamp(row);
            if (existing != null) existing.setVisibility(View.GONE);
            return;
        }

        Pojo pojo = result.getPojo();
        if (pojo == null) {
            BOUND_ROWS.remove(row);
            return;
        }

        TextView timestampView = ensureTimestampView(row, context);
        if (timestampView == null) {
            BOUND_ROWS.remove(row);
            return;
        }

        // Replace the row's identity before any asynchronous work is requested.
        BOUND_ROWS.put(row, new BoundRow(result, context));

        LaunchStatsProvider.LaunchStats stats = resolveStats(pojo);
        timestampView.setVisibility(View.VISIBLE);
        timestampView.setText(formatTimestamp(context, resolveTimestamp(pojo, stats), stats));
        SmartTextAppearance.applyHistoryMetadata(timestampView);

        ensureStatsLoaded(context);
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
        if (FIRST_SEEN.size() >= MAX_FIRST_SEEN_ENTRIES && !FIRST_SEEN.containsKey(key)) {
            FIRST_SEEN.clear();
        }
        return FIRST_SEEN.computeIfAbsent(key, ignored -> System.currentTimeMillis());
    }

    public static void invalidateStats() {
        statsLoadedAt = 0L;
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

    private static void ensureStatsLoaded(Context context) {
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
            Map<String, LaunchStatsProvider.LaunchStats> loaded = null;
            try {
                loaded = LaunchStatsProvider.loadAll(appContext);
            } finally {
                if (loaded != null) {
                    launchStats = loaded;
                    statsLoadedAt = System.currentTimeMillis();
                }
                loadInFlight = false;
            }

            if (loaded != null) MAIN.post(UniversalHistoryTimestamp::refreshBoundRows);
        });
    }

    /** Re-render every still-visible row from its current binding after the shared stats load. */
    private static void refreshBoundRows() {
        ArrayList<View> rows = new ArrayList<>(BOUND_ROWS.keySet());
        for (View row : rows) {
            if (row == null) continue;
            BoundRow binding = BOUND_ROWS.get(row);
            Context context = binding == null ? null : binding.context.get();
            if (binding == null || context == null || !row.isAttachedToWindow()) {
                BOUND_ROWS.remove(row);
                continue;
            }
            bind(row, binding.result, context);
        }
    }

    private static TextView ensureTimestampView(View row, Context context) {
        // All current launcher result layouts already provide this stable slot. Reuse it instead
        // of dynamically appending a second metadata TextView to every history row.
        View stable = row.findViewById(R.id.item_history_meta);
        if (stable instanceof TextView) {
            TextView timestamp = (TextView) stable;
            timestamp.setTag(VIEW_TAG);
            return timestamp;
        }

        TextView existing = findTaggedTimestamp(row);
        if (existing != null) return existing;

        // Compatibility fallback for result layouts that do not yet include item_history_meta.
        LinearLayout container = findBestVerticalTextContainer(row);
        if (container == null) return null;

        TextView timestamp = new AutoMarqueeTextView(context);
        timestamp.setTag(VIEW_TAG);
        timestamp.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        timestamp.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 2);
        container.addView(timestamp, params);
        return timestamp;
    }

    private static TextView findTimestamp(View row) {
        View stable = row.findViewById(R.id.item_history_meta);
        if (stable instanceof TextView) return (TextView) stable;
        return findTaggedTimestamp(row);
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

    private static final class BoundRow {
        final Result<?> result;
        final WeakReference<Context> context;

        BoundRow(Result<?> result, Context context) {
            this.result = result;
            this.context = new WeakReference<>(context);
        }
    }
}
