package fr.neamar.kiss.ui;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class GlobalTextStylerTest {
    @Test
    void weightIsClampedToSupportedRange() {
        assertThat(GlobalTextStyler.clampWeight(20), is(100));
        assertThat(GlobalTextStyler.clampWeight(100), is(100));
        assertThat(GlobalTextStyler.clampWeight(400), is(400));
        assertThat(GlobalTextStyler.clampWeight(700), is(700));
        assertThat(GlobalTextStyler.clampWeight(900), is(900));
        assertThat(GlobalTextStyler.clampWeight(1200), is(900));
    }
}
