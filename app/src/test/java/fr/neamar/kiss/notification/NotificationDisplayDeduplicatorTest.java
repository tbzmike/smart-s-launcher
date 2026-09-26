package fr.neamar.kiss.notification;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class NotificationDisplayDeduplicatorTest {
    @Test void collapsesSameVisibleEventFromTwoAndroidSlots() {
        assertThat(NotificationDisplayDeduplicator.isNearDuplicate(
                "com.whatsapp", "chat-group", "Andronicah", "Bread and chips", 1_000_000L,
                "com.whatsapp", "chat-group", " Andronicah ", "Bread   and chips", 1_020_000L),
                is(true));
    }

    @Test void keepsSameTextWhenEventsAreFarApart() {
        assertThat(NotificationDisplayDeduplicator.isNearDuplicate(
                "com.whatsapp", "chat-group", "Andronicah", "Bread and chips", 1_000_000L,
                "com.whatsapp", "chat-group", "Andronicah", "Bread and chips", 1_200_000L),
                is(false));
    }

    @Test void keepsDifferentNotificationGroupsSeparate() {
        assertThat(NotificationDisplayDeduplicator.isNearDuplicate(
                "com.whatsapp", "chat-a", "Message", "Hello", 1_000_000L,
                "com.whatsapp", "chat-b", "Message", "Hello", 1_010_000L),
                is(false));
    }

    @Test void persistedPackageFallbackCanMatchLiveConversationGroup() {
        assertThat(NotificationDisplayDeduplicator.isNearDuplicate(
                "com.whatsapp", "com.whatsapp", "Andronicah", "Bread and chips", 1_000_000L,
                "com.whatsapp", "conversation-42", "Andronicah", "Bread and chips", 1_010_000L),
                is(true));
    }

    @Test void emptyPresentationIsNeverCollapsed() {
        assertThat(NotificationDisplayDeduplicator.isNearDuplicate(
                "android", "system", "", "", 1_000_000L,
                "android", "system", "", "", 1_001_000L),
                is(false));
    }
}
