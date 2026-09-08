package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

public class VerticalCardWidthPolicyTest {
    @Test
    public void belowOneHundredShrinksCardButKeepsBaseInsets() {
        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 48), is(480));
        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 48), is(8));
        assertThat(VerticalCardWidthPolicy.insetForPercent(4, 48), is(4));
    }

    @Test
    public void oneHundredUsesViewportWithoutChangingNormalInsets() {
        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 100), is(1000));
        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 100), is(8));
        assertThat(VerticalCardWidthPolicy.insetForPercent(4, 100), is(4));
    }

    @Test
    public void oneHundredFiftyUsesFullViewportAndHalfInsets() {
        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 150), is(1000));
        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 150), is(4));
        assertThat(VerticalCardWidthPolicy.insetForPercent(4, 150), is(2));
    }

    @Test
    public void twoHundredUsesFullViewportWithNoInternalSideGutter() {
        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 200), is(1000));
        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 200), is(0));
        assertThat(VerticalCardWidthPolicy.insetForPercent(4, 200), is(0));
    }

    @Test
    public void valuesAboveMaximumCannotCreateOffscreenWidth() {
        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 500), is(1000));
        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 500), is(0));
    }
}
