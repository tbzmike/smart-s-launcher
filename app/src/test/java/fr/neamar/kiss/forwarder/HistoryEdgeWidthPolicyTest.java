package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class HistoryEdgeWidthPolicyTest {
    @Test void oneHundredPreservesNormalWidthInsetsAndBounds() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 100), is(600));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 100), is(8));
        assertThat((double) HistoryEdgeWidthPolicy.leftBoundForPercent(0.10f, 100), closeTo(0.10, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.rightBoundForPercent(0.90f, 100), closeTo(0.90, 0.0001));
    }

    @Test void oneHundredFiftyMovesHalfwayToPhysicalEdges() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 150), is(800));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 150), is(4));
        assertThat((double) HistoryEdgeWidthPolicy.leftBoundForPercent(0.10f, 150), closeTo(0.05, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.rightBoundForPercent(0.90f, 150), closeTo(0.95, 0.0001));
    }

    @Test void twoHundredMeansExactViewportEdges() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 200), is(1000));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 200), is(0));
        assertThat((double) HistoryEdgeWidthPolicy.leftBoundForPercent(0.10f, 200), closeTo(0.0, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.rightBoundForPercent(0.90f, 200), closeTo(1.0, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.viewportScaleForPercent(200), closeTo(1.0, 0.0001));
    }

    @Test void threeHundredExtendsQuarterViewportPastEachEdge() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 300), is(1500));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 300), is(0));
        assertThat((double) HistoryEdgeWidthPolicy.leftBoundForPercent(0.10f, 300), closeTo(-0.25, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.rightBoundForPercent(0.90f, 300), closeTo(1.25, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.viewportScaleForPercent(300), closeTo(1.5, 0.0001));
    }

    @Test void fourHundredSpansTwoViewportWidths() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 400), is(2000));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 400), is(0));
        assertThat((double) HistoryEdgeWidthPolicy.leftBoundForPercent(0.10f, 400), closeTo(-0.50, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.rightBoundForPercent(0.90f, 400), closeTo(1.50, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.viewportScaleForPercent(400), closeTo(2.0, 0.0001));
    }

    @Test void valuesAboveMaximumClampAtFourHundred() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 500), is(2000));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 500), is(0));
        assertThat((double) HistoryEdgeWidthPolicy.leftBoundForPercent(0.10f, 500), closeTo(-0.50, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.rightBoundForPercent(0.90f, 500), closeTo(1.50, 0.0001));
    }

    @Test void valuesBelowOneHundredShrinkFromNormalWidth() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 50), is(300));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 50), is(8));
    }

    @Test void normalWidthLargerThanViewportStillUsesViewportAsEdgeReference() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(1200, 1000, 100), is(1000));
        assertThat(HistoryEdgeWidthPolicy.targetWidth(1200, 1000, 200), is(1000));
        assertThat(HistoryEdgeWidthPolicy.targetWidth(1200, 1000, 400), is(2000));
    }
}
