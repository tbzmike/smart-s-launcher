package fr.neamar.kiss.ui;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.Date;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int MAX_FIRST_SEEN_ENTRIES = 512;
    private static final ConcurrentHashMap<String, Long> FIRST_SEEN = new ConcurrentHashMap<>();
    private static final LruCache<String, CharSequence> FORMATTED_CACHE = new LruCache<>(512);
    private static final WeakHashMap<TextView, Boolean> STYLED_VIEWS = new WeakHashMap<>();
    private static volatile Map<String, LaunchStatsProvider.LaunchStats> launchStats;

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
        long resolvedTimestamp = resolveTimestamp(pojo, stats);
        timestampView.setText(formatTimestampCached(context, pojo, resolvedTimestamp, stats));
        if (!STYLED_VIEWS.containsKey(timestampView)) {
            SmartTextAppearance.applyHistoryMetadata(timestampView);
            STYLED_VIEWS.put(timestampView, Boolean.TRUE);
        }
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
        launchStats = null;
        synchronized (FORMATTED_CACHE) {
            FORMATTED_CACHE.evictAll();
        }
    }

    /** Supplies one bulk stats snapshot loaded by the scroll-idle history enrichment pipeline. */
    public static void updateStats(Map<String, LaunchStatsProvider.LaunchStats> stats) {
        launchStats = stats == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(stats));
        synchronized (FORMATTED_CACHE) {
            FORMATTED_CACHE.evictAll();
        }
    }

    private static CharSequence formatTimestampCached(
            Context context, Pojo pojo, long timestamp, LaunchStatsProvider.LaunchStats stats) {
        int interactionsToday = stats == null ? 0 : Math.max(0, stats.launchesToday);
        String historyId = pojo.getHistoryId();
        if (TextUtils.isEmpty(historyId)) historyId = pojo.id;
        String locale = context.getResources().getConfiguration().locale.toLanguageTag();
        String key = historyId + '|' + timestamp + '|' + interactionsToday + '|'
                + DateFormat.is24HourFormat(context) + '|' + locale + '|'
                + TimeZone.getDefault().getID();
        synchronized (FORMATTED_CACHE) {
            CharSequence cached = FORMATTED_CACHE.get(key);
            if (cached != null) return cached;
        }
        CharSequence formatted = formatTimestamp(context, timestamp, interactionsToday);
        synchronized (FORMATTED_CACHE) {
            FORMATTED_CACHE.put(key, formatted);
        }
        return formatted;
    }

    private static CharSequence formatTimestamp(Context context, long timestamp,
                                                int interactionsToday) {
        Date date = new Date(timestamp);
        java.text.DateFormat dateFormat = DateFormat.getMediumDateFormat(context);
        java.text.DateFormat timeFormat = DateFormat.getTimeFormat(context);
        return new StringBuilder()
                .append(dateFormat.format(date))
                .append("  •  ")
                .append(timeFormat.format(date))
                .append("  •  ")
                .append(interactionsToday)
                .append(interactionsToday == 1 ? " interaction today" : " interactions today");
    }

    private static TextView ensureTimestampView(View row, Context context) {
        TextView existing = findTaggedTimestamp(row);
        if (existing != null) return existing;

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
