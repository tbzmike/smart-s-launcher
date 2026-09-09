package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class HistoryLayoutModeTest {
    @Test
    void keepsOnlyTheThreeSupportedModes() {
        assertThat(HistoryLayoutMode.normalize("vertical"), is("vertical"));
        assertThat(HistoryLayoutMode.normalize("vertical_cards"), is("vertical_cards"));
        assertThat(HistoryLayoutMode.normalize("wheel_3d"), is("wheel_3d"));
    }

    @Test
    void migratesEveryRemovedOrInvalidModeToVertical() {
        assertThat(HistoryLayoutMode.normalize("horizontal_icons"), is("vertical"));
        assertThat(HistoryLayoutMode.normalize("horizontal_cards"), is("vertical"));
        assertThat(HistoryLayoutMode.normalize("horizontal_names"), is("vertical"));
        assertThat(HistoryLayoutMode.normalize("square_u"), is("vertical"));
        assertThat(HistoryLayoutMode.normalize("unknown"), is("vertical"));
        assertThat(HistoryLayoutMode.normalize(null), is("vertical"));
    }
}
