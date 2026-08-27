package fr.neamar.kiss.adapter;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class HistoryClickOrderTest {
    @Test
    void clickedItemFromMiddleMovesToBottom() {
        List<String> history = new ArrayList<>(Arrays.asList("A", "B", "C", "D"));

        assertThat(HistoryClickOrder.moveToEnd(history, "B"), is(true));
        assertThat(history, contains("A", "C", "D", "B"));
    }

    @Test
    void previousLaunchMovesUpAsNewerItemsAreClicked() {
        List<String> history = new ArrayList<>(Arrays.asList("A", "B", "C", "D"));

        HistoryClickOrder.moveToEnd(history, "B");
        HistoryClickOrder.moveToEnd(history, "A");

        assertThat(history, contains("C", "D", "B", "A"));
    }

    @Test
    void alreadyBottomItemIsNotReordered() {
        List<String> history = new ArrayList<>(Arrays.asList("A", "B", "C"));

        assertThat(HistoryClickOrder.moveToEnd(history, "C"), is(false));
        assertThat(history, contains("A", "B", "C"));
    }
}
