package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class StableViewportAnchorTest {
    @Test
    void followsTheSameCardWhenHistoryIsReordered() {
        assertThat(StableViewportAnchor.resolveIndex(
                "current-card", 1,
                Arrays.asList("new-entry", "older-card", "current-card", "latest-card")),
                is(2));
    }

    @Test
    void missingIdentityNeverFallsThroughToAnUnrelatedOldIndex() {
        assertThat(StableViewportAnchor.resolveIndex(
                "removed-card", 2,
                Arrays.asList("three-hours-old", "two-hours-old", "unrelated-card")),
                is(-1));
    }

    @Test
    void legacyIndexIsUsedOnlyWhenNoStableIdentityExists() {
        assertThat(StableViewportAnchor.resolveIndex(
                null, 1, Arrays.asList("first", "second", "third")), is(1));
    }
}
