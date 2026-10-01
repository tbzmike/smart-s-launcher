package fr.neamar.kiss.utils;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class SavedNotificationDestinationResolverTest {

    @Test
    void appFallbackIsAcceptedAsSuccessfulUserOpen() {
        assertThat(
                SavedNotificationDestinationResolver.OpenResult.APP_FALLBACK_OPENED.accepted(),
                is(true));
    }

    @Test
    void missingExactTargetWithoutAppFallbackIsNotAccepted() {
        assertThat(
                SavedNotificationDestinationResolver.OpenResult.NO_EXACT_TARGET.accepted(),
                is(false));
    }

    @Test
    void retryingExactRouteRemainsAccepted() {
        assertThat(
                SavedNotificationDestinationResolver.OpenResult.LISTENER_RETRY_STARTED.accepted(),
                is(true));
        assertThat(
                SavedNotificationDestinationResolver.OpenResult.ENABLE_RETRY_STARTED.accepted(),
                is(true));
    }
}
