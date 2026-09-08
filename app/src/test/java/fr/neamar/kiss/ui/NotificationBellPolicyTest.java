package fr.neamar.kiss.ui;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class NotificationBellPolicyTest {
    @Test void appOrShortcutHistoryTileDoesNotGainBellFromAttachedNotificationContent() {
        assertThat(NotificationBellPolicy.shouldShow(false, false), is(false));
    }

    @Test void actualNotificationEventsAlwaysKeepTheirBell() {
        assertThat(NotificationBellPolicy.shouldShow(true, false), is(true));
        assertThat(NotificationBellPolicy.shouldShow(false, true), is(true));
    }

    @Test void onlyUnreadVerticalListNotificationBellFlashes() {
        assertThat(NotificationBellPolicy.shouldFlash(true, true, true), is(true));
        assertThat(NotificationBellPolicy.shouldFlash(true, false, true), is(false));
        assertThat(NotificationBellPolicy.shouldFlash(true, true, false), is(false));
        assertThat(NotificationBellPolicy.shouldFlash(false, true, true), is(false));
    }
}
