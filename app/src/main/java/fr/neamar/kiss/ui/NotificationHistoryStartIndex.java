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

        // Group notification pojos identify their newest child by post time rather than child id.
        if (postTime > 0L) {
            for (int i = 0; i < records.size(); i++) {
                NotificationHistoryRecord record = records.get(i);
                if (record != null && postTime == record.postTime) return i;
            }
        }

        if (notificationId != null && !notificationId.isEmpty()) {
            for (int i = 0; i < records.size(); i++) {
                NotificationHistoryRecord record = records.get(i);
                if (record != null && notificationId.equals(record.notificationId)) return i;
            }
        }

        return -1;
    }
}
