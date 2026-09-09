package fr.neamar.kiss.searcher;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class HomeHistoryWindowTest {
    @Test void cachedRecentItemMovesToNewestWithoutDuplication() {
        List<String> result = HomeHistoryWindow.select(
                Arrays.asList("A", "B", "C"), "B", 3, Function.identity(), value -> true);
        assertThat(result, contains("A", "C", "B"));
    }

    @Test void newRecentItemDropsOnlyTheOldestWhenWindowIsFull() {
        List<String> result = HomeHistoryWindow.select(
                Arrays.asList("A", "B", "C"), "D", 3, Function.identity(), value -> true);
        assertThat(result, contains("B", "C", "D"));
    }

    @Test void eligibilityAndZeroLimitAreHonored() {
        List<String> filtered = HomeHistoryWindow.select(
                Arrays.asList("A", "B", "C"), "D", 3, Function.identity(), value -> !"C".equals(value));
        assertThat(filtered, contains("A", "B", "D"));
        assertThat(HomeHistoryWindow.select(
                Collections.singletonList("A"), "B", 0, Function.identity(), value -> true), empty());
    }
}
