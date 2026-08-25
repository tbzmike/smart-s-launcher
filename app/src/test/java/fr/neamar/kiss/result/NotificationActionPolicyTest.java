package fr.neamar.kiss.result;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class NotificationActionPolicyTest {
    @Test
    void verifiedActiveIndividualHasExactAction() {
        assertThat(NotificationActionPolicy.resolve(true, false, true, 0),
                is(NotificationActionPolicy.Target.INDIVIDUAL));
    }

    @Test
    void persistedIndividualNeverControlsAnotherGroupNotification() {
        assertThat(NotificationActionPolicy.resolve(true, false, false, 4),
                is(NotificationActionPolicy.Target.NONE));
    }

    @Test
    void legacyGroupRequiresAtLeastOneVerifiedActiveNotification() {
        assertThat(NotificationActionPolicy.resolve(false, true, false, 2),
                is(NotificationActionPolicy.Target.GROUP));
        assertThat(NotificationActionPolicy.resolve(false, true, false, 0),
                is(NotificationActionPolicy.Target.NONE));
    }

    @Test
    void unrelatedPersistedRecordHasNoNotificationAction() {
        assertThat(NotificationActionPolicy.resolve(false, false, true, 3),
                is(NotificationActionPolicy.Target.NONE));
    }
}
