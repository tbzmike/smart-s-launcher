package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class PixelFavoritesPolicyTest {
    @Test
    void pinnedFavoritesAlwaysOccupySlotsFirst() {
        List<String> result = PixelFavoritesPolicy.select(
                Arrays.asList("fav-a", "fav-b"),
                Arrays.asList("used-a", "fav-a", "used-b"),
                4);

        assertThat(result, contains("fav-a", "fav-b", "used-a", "used-b"));
    }

    @Test
    void pixelModeNeverExceedsTenItems() {
        List<String> result = PixelFavoritesPolicy.select(
                Collections.emptyList(),
                Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"),
                99);

        assertThat(result, hasSize(10));
        assertThat(PixelFavoritesPolicy.clampMaxApps(99), is(10));
    }

    @Test
    void pinnedFavoritesCanFillAllPixelSlotsWithoutSuggestions() {
        List<String> result = PixelFavoritesPolicy.select(
                Arrays.asList("fav-a", "fav-b", "fav-c"),
                Arrays.asList("used-a", "used-b"),
                2);

        assertThat(result, contains("fav-a", "fav-b"));
    }

    @Test
    void duplicateSuggestedAppsDoNotConsumeExtraSlots() {
        List<String> result = PixelFavoritesPolicy.select(
                Collections.singletonList("fav-a"),
                Arrays.asList("used-a", "used-a", "used-b"),
                3);

        assertThat(result, contains("fav-a", "used-a", "used-b"));
    }

    @Test
    void minimumPixelSlotCountIsOne() {
        assertThat(PixelFavoritesPolicy.clampMaxApps(0), is(1));
    }
}
