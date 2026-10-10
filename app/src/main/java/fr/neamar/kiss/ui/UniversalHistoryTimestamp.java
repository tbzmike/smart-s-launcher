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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.db.AppUsageTodayStore;
import fr.neamar.kiss.db.LaunchStatsProvider;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.utils.AppIdentityResolver;

/**
 * Guarantees that every record rendered on the launcher history/home list has a dedicated
 * timestamp line underneath its normal content. The same row also shows the number of launcher
 * interactions recorded for that exact history item during the current local calendar day.
 */
public final class UniversalHistoryTimestamp {
    private static final String VIEW_TAG = "smart_s_universal_history_timestamp";
    private static final LruCache<String, CharSequence> FORMATTED_CACHE = new LruCache<>(512);
    private static final AtomicLong STATS_GENERATION = new AtomicLong();
    private static volatile Map<String, LaunchStatsProvider.LaunchStats> launchStats;
    private static volatile AppUsageTodayStore.Snapshot usageSnapshot;

    private UniversalHistoryTimestamp() {}

    public static void bind(@NonNull View row, @NonNull Result<?> result, @NonNull Context context) {
        if (!isHistorySurface(context)) {
            clearTimestamp(row);
            return;
        }

        Pojo pojo = result.getPojo();
        if (pojo == null) return;

        TextView timestampView = ensureTimestampView(row, context);
        if (timestampView == null) return;

        LaunchStatsProvider.LaunchStats stats = resolveStats(pojo);
        long resolvedTimestamp = resolveTimestamp(pojo, stats);
        CharSequence formatted = formatTimestampCached(
                context, pojo, resolvedTimestamp, stats, usageSnapshot);
        if (!TextUtils.equals(timestampView.getText(), formatted)) {
            timestampView.setText(formatted);
        }
        if (timestampView.getVisibility() != View.VISIBLE) {
            timestampView.setVisibility(View.VISIBLE);
        }
        // Global scaling and search typography can touch the same recycled view between binds.
        // Reapply the dedicated metadata preferences after those owners instead of treating a
        // once-styled TextView as permanently configured.
        SmartTextAppearance.applyHistoryMetadata(timestampView);
    }

    /** True only while launcher rows belong to the normal empty-query History/Home surface. */
    public static boolean isHistorySurface(@NonNull Context context) {
        if (!(context instanceof MainActivity)) return false;
        MainActivity activity = (MainActivity) context;
        return !activity.isViewingAllApps()
                && (activity.searchEditText == null || activity.searchEditText.length() == 0);
    }

    private static void clearTimestamp(View row) {
        TextView existing = findTimestamp(row);
        if (existing == null) return;
        // List rows are recycled across History and QUERY. Hiding the metadata view alone leaves
        // its previous enriched text attached to that recycled row; an asynchronous History
        // enrichment finishing after search starts can then append the same suffix again and again.
        if (!TextUtils.isEmpty(existing.getText())) existing.setText(null);
        if (existing.getVisibility() != View.GONE) existing.setVisibility(View.GONE);
    }

    private static LaunchStatsProvider.LaunchStats resolveStats(Pojo pojo) {
        Map<String, LaunchStatsProvider.LaunchStats> snapshot = launchStats;
        String historyId = pojo.getHistoryId();
        if (snapshot == null || TextUtils.isEmpty(historyId)) return null;
        return snapshot.get(historyId);
    }

    /** Timestamp used to place a Home/History row on the date navigator. */
    public static long resolveHistoryTimestamp(@NonNull Result<?> result) {
        Pojo pojo = result.getPojo();
        if (pojo == null) return 0L;
        return resolveTimestamp(pojo, resolveStats(pojo));
    }

    /**
     * Human-friendly section label for Home history navigation.
     * Examples: Today, Yesterday, Wednesday, Sep 30, 2026.
     */
    @NonNull
    public static String formatHistorySectionLabel(@NonNull Context context, long timestamp) {
        if (timestamp <= 0L) return "History";

        Calendar now = Calendar.getInstance();
        Calendar value = Calendar.getInstance();
        value.setTimeInMillis(timestamp);

        if (sameLocalDay(now, value)) return "Today";

        Calendar yesterday = (Calendar) now.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameLocalDay(yesterday, value)) return "Yesterday";

        Calendar sixDaysAgo = (Calendar) now.clone();
        sixDaysAgo.add(Calendar.DAY_OF_YEAR, -6);
        if (!value.before(startOfLocalDay(sixDaysAgo))
                && value.before(startOfLocalDay(now))) {
            java.text.DateFormat weekday = new SimpleDateFormat(
                    "EEEE", context.getResources().getConfiguration().locale);
            return weekday.format(new Date(timestamp));
        }

        java.text.DateFormat dateFormat = DateFormat.getMediumDateFormat(context);
        return dateFormat.format(new Date(timestamp));
    }

    private static boolean sameLocalDay(Calendar left, Calendar right) {
        return left.get(Calendar.ERA) == right.get(Calendar.ERA)
                && left.get(Calendar.YEAR) == right.get(Calendar.YEAR)
                && left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR);
    }

    private static Calendar startOfLocalDay(Calendar source) {
        Calendar copy = (Calendar) source.clone();
        copy.set(Calendar.HOUR_OF_DAY, 0);
        copy.set(Calendar.MINUTE, 0);
        copy.set(Calendar.SECOND, 0);
        copy.set(Calendar.MILLISECOND, 0);
        return copy;
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
        return stats == null ? 0L : Math.max(0L, stats.lastLaunchTime);
    }

    public static void invalidateStats() {
        STATS_GENERATION.incrementAndGet();
        // Keep the last published values visible while the idle loader obtains a replacement.
        // A cancelled refresh must not turn valid timestamps into "unavailable" indefinitely.
        synchronized (FORMATTED_CACHE) {
            FORMATTED_CACHE.evictAll();
        }
    }

    public static long statsGeneration() {
        return STATS_GENERATION.get();
    }

    /** Supplies one bulk enrichment snapshot without forcing already-visible rows to re-layout. */
    public static void updateEnrichment(Map<String, LaunchStatsProvider.LaunchStats> stats,
                                        AppUsageTodayStore.Snapshot usage) {
        launchStats = stats == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(stats));
        usageSnapshot = usage;
        synchronized (FORMATTED_CACHE) {
            FORMATTED_CACHE.evictAll();
        }
    }

    /** Compatibility entry point for callers that only have launch statistics. */
    public static void updateStats(Map<String, LaunchStatsProvider.LaunchStats> stats) {
        updateEnrichment(stats, usageSnapshot);
    }

    private static CharSequence formatTimestampCached(
            Context context, Pojo pojo, long timestamp, LaunchStatsProvider.LaunchStats stats,
            AppUsageTodayStore.Snapshot usage) {
        boolean notification = pojo instanceof NotificationPojo;
        long interactionsToday = notification
                ? TileLaunchCounter.getToday(context, pojo)
                : stats == null ? 0L : Math.max(0, stats.launchesToday);
        long totalInteractions = notification
                ? TileLaunchCounter.getTotal(context, pojo)
                : stats == null ? 0L : Math.max(0, stats.totalLaunches);

        long foregroundMs = 0L;
        boolean usageAvailable = usage != null && usage.available;
        String usagePackage = usagePackage(context, pojo);
        if (!TextUtils.isEmpty(usagePackage) && usageAvailable) {
            Long value = usage.foregroundMsByPackage.get(usagePackage);
            foregroundMs = value == null ? 0L : Math.max(0L, value);
        }

        String historyId = pojo.getHistoryId();
        if (TextUtils.isEmpty(historyId)) historyId = pojo.id;
        String locale = context.getResources().getConfiguration().locale.toLanguageTag();
        String key = historyId + '|' + timestamp + '|' + interactionsToday + '|'
                + totalInteractions + '|' + foregroundMs + '|' + usageAvailable + '|'
                + DateFormat.is24HourFormat(context) + '|' + locale + '|'
                + TimeZone.getDefault().getID();
        synchronized (FORMATTED_CACHE) {
            CharSequence cached = FORMATTED_CACHE.get(key);
            if (cached != null) return cached;
        }
        CharSequence formatted = formatTimestamp(
                context, pojo, timestamp, interactionsToday, totalInteractions,
                foregroundMs, usageAvailable);
        synchronized (FORMATTED_CACHE) {
            FORMATTED_CACHE.put(key, formatted);
        }
        return formatted;
    }

    private static CharSequence formatTimestamp(Context context, Pojo pojo, long timestamp,
                                                long interactionsToday, long totalInteractions,
                                                long foregroundMs, boolean usageAvailable) {
        StringBuilder text = new StringBuilder();
        if (timestamp > 0L) {
            text.append(pojo instanceof NotificationPojo ? "Received " : "History time ")
                    .append(formatFullTimestamp(context, timestamp));
        } else {
            text.append("History time unavailable");
        }

        if (pojo instanceof NotificationPojo) {
            text.append("  •  Opened today ").append(interactionsToday)
                    .append("  •  Opened total ").append(totalInteractions);
        } else {
            text.append("  •  Launched today ").append(interactionsToday)
                    .append("  •  Total launches ").append(totalInteractions);
        }

        if (!TextUtils.isEmpty(usagePackage(context, pojo))) {
            text.append("  •  Used today ")
                    .append(usageAvailable ? formatDuration(foregroundMs) : "unavailable");
        }
        return text;
    }

    private static String formatFullTimestamp(Context context, long timestamp) {
        Date date = new Date(timestamp);
        java.text.DateFormat dateFormat = DateFormat.getMediumDateFormat(context);
        String timePattern = DateFormat.is24HourFormat(context) ? "HH:mm:ss" : "h:mm:ss a";
        java.text.DateFormat timeFormat = new SimpleDateFormat(
                timePattern, context.getResources().getConfiguration().locale);
        return dateFormat.format(date) + " " + timeFormat.format(date);
    }

    /**
     * App-backed wrapper shortcuts share Android's package usage total with the real app. Ordinary
     * in-app shortcuts remain independent and therefore return no package here.
     */
    private static String usagePackage(Context context, Pojo pojo) {
        try {
            String canonical = AppIdentityResolver.canonicalPackage(
                    context, KissApplication.getApplication(context).getDataHandler(), pojo);
            if (!TextUtils.isEmpty(canonical)) return canonical;
        } catch (RuntimeException ignored) {
            // Rendering metadata must never fail because a provider is being replaced.
        }
        if (pojo instanceof AppPojo) return ((AppPojo) pojo).packageName;
        if (pojo instanceof DisabledAppPojo) return ((DisabledAppPojo) pojo).targetPackage;
        if (pojo instanceof ShortcutPojo) return ((ShortcutPojo) pojo).packageName;
        if (pojo instanceof NotificationPojo) return ((NotificationPojo) pojo).packageName;
        return null;
    }

    private static String formatDuration(long durationMs) {
        long totalMinutes = Math.max(0L, durationMs) / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return hours > 0L ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private static TextView ensureTimestampView(View row, Context context) {
        // Every current history row layout already exposes this stable slot. Reusing it prevents
        // a second metadata TextView from being appended beside the enrichment metadata and keeps
        // row measurement/recycling deterministic.
        View stable = row.findViewById(R.id.item_history_meta);
        if (stable instanceof TextView) {
            TextView timestamp = (TextView) stable;
            timestamp.setTag(VIEW_TAG);
            return timestamp;
        }

        TextView existing = findTaggedTimestamp(row);
        if (existing != null) return existing;

        // Compatibility fallback for a custom/legacy result layout without item_history_meta.
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
}
