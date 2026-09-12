package fr.neamar.kiss.notification;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class NotificationEventIdentityTest {
    @Test void exactVisibleNotificationContentMatches() {
        assertThat(NotificationEventIdentity.hasSameVisibleContent(
                "Selfies", "New memory for you", "Selfies", "New memory for you"), is(true));
    }

    @Test void caseAndWhitespaceDoNotBreakTheSameVisibleMessage() {
        assertThat(NotificationEventIdentity.hasSameVisibleContent(
                "  SELFIES ", "New\n memory   for you", "selfies", "new memory for YOU"),
                is(true));
    }

    @Test void reusedKeyWithAnotherMessageDoesNotMatch() {
        assertThat(NotificationEventIdentity.hasSameVisibleContent(
                "Photos", "Memory from 2025", "Photos", "Memory from 2026"), is(false));
    }

    @Test void reusedKeyWithAnotherTitleDoesNotMatch() {
        assertThat(NotificationEventIdentity.hasSameVisibleContent(
                "Alice", "Hello", "Bob", "Hello"), is(false));
    }

    @Test void emptyHistoryCannotAuthorizeAReplacementRoute() {
        assertThat(NotificationEventIdentity.hasSameVisibleContent(
                null, "", "Photos", "New memory for you"), is(false));
    }
}
