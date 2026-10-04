package fr.neamar.kiss.ui;

import java.util.List;

import fr.neamar.kiss.db.NotificationHistoryRecord;

/** Resolves the persisted history row that corresponds to the notification the user selected. */
public final class NotificationHistoryStartIndex {
    private NotificationHistoryStartIndex() {}

    public static int resolve(List<NotificationHistoryRecord> records,
                              String notificationId,
                              long postTime) {
        if (records == null || records.isEmpty()) return -1;

        if (postTime > 0L && notificationId != null && !notificationId.isEmpty()) {
            for (int i = 0; i < records.size(); i++) {
                NotificationHistoryRecord record = records.get(i);
                if (record != null
                        && postTime == record.postTime
                        && notificationId.equals(record.notificationId)) {
                    return i;
                }
            }
        }

        // Only a legacy/group pojo is allowed to resolve by child post time alone. For an
        // individual notification row, a failed exact id+time match must stay failed; otherwise a
        // different notification posted in the same millisecond (or a reused id at another time)
        // could be opened under the user's finger.
        if (postTime > 0L
                && notificationId != null
                && notificationId.startsWith("notification-group://")) {
            for (int i = 0; i < records.size(); i++) {
                NotificationHistoryRecord record = records.get(i);
                if (record != null && postTime == record.postTime) return i;
            }
        }

        // Id-only matching is intentionally limited to old callers that have no usable event time.
        if (postTime <= 0L && notificationId != null && !notificationId.isEmpty()) {
            for (int i = 0; i < records.size(); i++) {
                NotificationHistoryRecord record = records.get(i);
                if (record != null && notificationId.equals(record.notificationId)) return i;
            }
        }

        return -1;
    }
}
