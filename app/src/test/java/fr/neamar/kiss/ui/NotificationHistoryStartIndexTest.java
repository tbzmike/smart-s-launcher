package fr.neamar.kiss.ui;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import fr.neamar.kiss.db.NotificationHistoryRecord;

class NotificationHistoryStartIndexTest {
    @Test
    void exactNotificationIdAndPostTimeSelectsThatStoredEntry() {
        NotificationHistoryRecord newest = record(31L, "notification://same", 300L);
        NotificationHistoryRecord selected = record(22L, "notification://same", 200L);
        NotificationHistoryRecord oldest = record(13L, "notification://other", 100L);

        assertThat(NotificationHistoryStartIndex.resolve(
                Arrays.asList(newest, selected, oldest), "notification://same", 200L), is(1));
    }

    @Test
    void groupSelectionFallsBackToMatchingNewestChildPostTime() {
        NotificationHistoryRecord newest = record(31L, "notification://child-a", 300L);
        NotificationHistoryRecord selected = record(22L, "notification://child-b", 200L);

        assertThat(NotificationHistoryStartIndex.resolve(
                Arrays.asList(newest, selected), "notification-group://conversation", 200L), is(1));
    }

    @Test
    void reusedNotificationIdWithoutUsableTimeSelectsNewestMatchingOccurrence() {
        NotificationHistoryRecord newest = record(31L, "notification://same", 300L);
        NotificationHistoryRecord older = record(22L, "notification://same", 200L);

        assertThat(NotificationHistoryStartIndex.resolve(
                Arrays.asList(newest, older), "notification://same", 0L), is(0));
    }

    @Test
    void missingSelectionDoesNotSilentlyOpenLatestHistoryEntry() {
        NotificationHistoryRecord newest = record(31L, "notification://newest", 300L);

        assertThat(NotificationHistoryStartIndex.resolve(
                Collections.singletonList(newest), "notification://missing", 200L), is(-1));
        assertThat(NotificationHistoryStartIndex.resolve(Collections.emptyList(), "x", 1L), is(-1));
    }

    private static NotificationHistoryRecord record(long dbId, String notificationId, long postTime) {
        NotificationHistoryRecord record = new NotificationHistoryRecord();
        record.dbId = dbId;
        record.notificationId = notificationId;
        record.postTime = postTime;
        return record;
    }
}
