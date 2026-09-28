package fr.neamar.kiss.notification;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

class MediaEventIdentityTest {
    @Test void sameExactMediaPostKeepsSameStorageIdentity() {
        String first = MediaEventIdentity.create(
                "com.whatsapp", "notification://exact-child", 1000L);
        String again = MediaEventIdentity.create(
                "com.whatsapp", "notification://exact-child", 1000L);

        assertThat(first, is(again));
    }

    @Test void sameAppDifferentNotificationNeverSharesArtworkIdentity() {
        String call = MediaEventIdentity.create(
                "com.whatsapp", "notification://call", 1000L);
        String message = MediaEventIdentity.create(
                "com.whatsapp", "notification://message", 1000L);

        assertThat(call, not(message));
    }

    @Test void reusedNotificationSlotAtAnotherPostTimeNeverSharesArtworkIdentity() {
        String oldPost = MediaEventIdentity.create(
                "com.example.player", "notification://player", 1000L);
        String newPost = MediaEventIdentity.create(
                "com.example.player", "notification://player", 2000L);

        assertThat(oldPost, not(newPost));
    }

    @Test void differentAppsNeverShareArtworkIdentity() {
        String first = MediaEventIdentity.create(
                "com.example.one", "notification://slot", 1000L);
        String second = MediaEventIdentity.create(
                "com.example.two", "notification://slot", 1000L);

        assertThat(first, not(second));
    }
}
