package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class WidgetLayerOrderTest {
    @Test
    void selectedWidgetMovesToThePersistedFront() {
        List<String> order = new ArrayList<>(Arrays.asList("bottom", "middle", "top"));

        assertThat(WidgetLayerOrder.bringToFront(order, "bottom"), is(true));
        assertThat(order, contains("middle", "top", "bottom"));
    }

    @Test
    void widgetCanBeSentBehindOverlappingWidgets() {
        List<String> order = new ArrayList<>(Arrays.asList("bottom", "middle", "top"));

        assertThat(WidgetLayerOrder.sendToBack(order, "top"), is(true));
        assertThat(order, contains("top", "bottom", "middle"));
    }

    @Test
    void alreadyCorrectLayerDoesNotTriggerAnUnnecessaryWrite() {
        List<String> order = new ArrayList<>(Arrays.asList("bottom", "top"));

        assertThat(WidgetLayerOrder.bringToFront(order, "top"), is(false));
        assertThat(WidgetLayerOrder.sendToBack(order, "bottom"), is(false));
        assertThat(order, contains("bottom", "top"));
    }
}
