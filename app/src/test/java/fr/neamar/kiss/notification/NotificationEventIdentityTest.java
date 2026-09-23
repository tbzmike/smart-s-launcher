package fr.neamar.kiss.notification;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class NotificationEventIdentityTest {
    @Test void exactVisibleNotificationContentRemainsCompatible() {
        assertThat(NotificationEventIdentity.hasSameVisibleContent(
                "Selfies", "New memory for you", "Selfies", "New memory for you"), is(true));
    }

    @Test void changedBodyDoesNotBreakSameAndroidNotificationSlot() {
        assertThat(NotificationEventIdentity.hasSameVisibleContent(
                "Photos", "Memory from 2025", "Photos", "Memory from 2026"), is(true));
    }

    @Test void changedTitleDoesNotBreakSameAndroidNotificationSlot() {
        assertThat(NotificationEventIdentity.hasSameVisibleContent(
                "ChatGPT is working", "Your response is in progress.",
                "Response ready", "Tap to return to ChatGPT to see your response."), is(true));
    }

    @Test void emptyPresentationTextDoesNotOverrideStableSlotIdentity() {
        assertThat(NotificationEventIdentity.hasSameVisibleContent(
                null, "", null, ""), is(true));
    }
}
