package fr.neamar.kiss.notification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

class NotificationRouteIdentityTest {
    @Test
    void sameExactMessageProducesSameReusableIdentity() {
        String first = NotificationRouteIdentity.create("notification://exact-child", 1000L);
        String again = NotificationRouteIdentity.create("notification://exact-child", 1000L);

        assertThat(first, is(again));
        assertThat(NotificationRouteIdentity.isValid(first), is(true));
        assertThat(NotificationRouteIdentity.requestCode(first), is(first.hashCode()));
    }

    @Test
    void reusedNotificationIdAtAnotherPostTimeGetsAnotherRoute() {
        String first = NotificationRouteIdentity.create("notification://same-id", 1000L);
        String later = NotificationRouteIdentity.create("notification://same-id", 2000L);

        assertThat(first, not(later));
    }

    @Test
    void differentExactNotificationGetsAnotherRoute() {
        String first = NotificationRouteIdentity.create("notification://child-a", 1000L);
        String second = NotificationRouteIdentity.create("notification://child-b", 1000L);

        assertThat(first, not(second));
    }

    @Test
    void rejectsMissingOrMalformedStoredIdentity() {
        assertThat(NotificationRouteIdentity.create(null, 1L), is(""));
        assertThat(NotificationRouteIdentity.create("", 1L), is(""));
        assertThat(NotificationRouteIdentity.isValid(null), is(false));
        assertThat(NotificationRouteIdentity.isValid("abc"), is(false));
        assertThat(NotificationRouteIdentity.isValid(
                "G000000000000000000000000000000000000000000000000000000000000000"),
                is(false));
    }
}
