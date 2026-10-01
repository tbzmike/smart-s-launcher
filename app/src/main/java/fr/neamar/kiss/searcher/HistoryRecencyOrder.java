package fr.neamar.kiss.searcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import fr.neamar.kiss.db.HistoryMode;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.RelevanceComparator;

/**
 * Authoritative ordering contract for the visible launcher History timeline.
 *
 * The database RECENCY query returns unique records newest-first. Searcher result queues emit lower
 * relevance first, so assigning larger relevance to earlier database rows produces an
 * oldest-to-newest visible list with the latest clicked item at the final/bottom position.
 */
final class HistoryRecencyOrder {
    static final HistoryMode MODE = HistoryMode.RECENCY;

    private HistoryRecencyOrder() {
    }

    static int relevanceForNewestFirstIndex(int itemCount, int newestFirstIndex) {
        if (itemCount <= 0 || newestFirstIndex < 0 || newestFirstIndex >= itemCount) return 0;
        return itemCount - newestFirstIndex;
    }

    /**
     * Freeze the intended oldest-to-newest presentation into list position. History Pojo objects
     * are shared with query/provider code and their relevance can change after this point; list
     * position must therefore stop depending on that mutable field.
     */
    static List<Pojo> freezeOldestToNewest(List<Pojo> items) {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        List<Pojo> ordered = new ArrayList<>(items);
        ordered.sort(new RelevanceComparator());
        return ordered;
    }

    /**
     * Build one real chronological timeline across launches, shortcuts, communications and
     * notifications. Oldest is index 0; the newest event is always the final/bottom item.
     *
     * App/shortcut time comes from the launch-history table. Notifications use Android post time,
     * and communication rows use their own event timestamp. Relevance is deliberately ignored:
     * relevance belongs to search ranking and must never be allowed to reorder History.
     */
    static List<Pojo> sortTimelineOldestToNewest(
            List<Pojo> items,
            Map<String, Long> launchTimes,
            Map<String, Long> launchSequences) {
        if (items == null || items.isEmpty()) return Collections.emptyList();

        final Map<String, Long> times =
                launchTimes == null ? Collections.emptyMap() : launchTimes;
        final Map<String, Long> sequences =
                launchSequences == null ? Collections.emptyMap() : launchSequences;

        List<Pojo> ordered = new ArrayList<>(items);
        ordered.sort((left, right) -> {
            long leftTime = eventTime(left, times);
            long rightTime = eventTime(right, times);
            int byTime = Long.compare(leftTime, rightTime);
            if (byTime != 0) return byTime;

            long leftSequence = launchSequence(left, sequences);
            long rightSequence = launchSequence(right, sequences);
            int bySequence = Long.compare(leftSequence, rightSequence);
            if (bySequence != 0) return bySequence;

            // Java's List.sort is stable, so returning 0 keeps source order for genuine ties such
            // as two Android notifications posted in the same millisecond.
            return 0;
        });
        return ordered;
    }

    static long eventTime(Pojo pojo, Map<String, Long> launchTimes) {
        if (pojo == null) return Long.MIN_VALUE;
        if (pojo instanceof NotificationPojo) {
            long postTime = ((NotificationPojo) pojo).postTime;
            if (postTime > 0L) return postTime;
        }
        if (pojo instanceof CommunicationPojo) {
            long eventTime = ((CommunicationPojo) pojo).timestamp;
            if (eventTime > 0L) return eventTime;
        }

        String historyId = pojo.getHistoryId();
        if (historyId != null && launchTimes != null) {
            Long value = launchTimes.get(historyId);
            if (value != null && value > 0L) return value;
        }
        return Long.MIN_VALUE;
    }

    private static long launchSequence(Pojo pojo, Map<String, Long> launchSequences) {
        if (pojo == null || launchSequences == null) return Long.MIN_VALUE;
        String historyId = pojo.getHistoryId();
        if (historyId == null) return Long.MIN_VALUE;
        Long value = launchSequences.get(historyId);
        return value == null ? Long.MIN_VALUE : value;
    }
}
