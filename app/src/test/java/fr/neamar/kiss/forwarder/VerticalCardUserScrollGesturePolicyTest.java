package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class VerticalCardUserScrollGesturePolicyTest {
    @Test
    void tapWithoutScrollNeverReportsMovement() {
        VerticalCardUserScrollGesturePolicy policy = new VerticalCardUserScrollGesturePolicy();
        policy.onTouchDown(420);
        assertThat(policy.onTouchMove(420), is(false));
        policy.onTouchEnd();
    }

    @Test
    void actualScrollChangeReportsExactlyOncePerGesture() {
        VerticalCardUserScrollGesturePolicy policy = new VerticalCardUserScrollGesturePolicy();
        policy.onTouchDown(420);
        assertThat(policy.onTouchMove(418), is(true));
        assertThat(policy.onTouchMove(390), is(false));
        policy.onTouchEnd();
    }

    @Test
    void nextGestureCanReportItsOwnMovement() {
        VerticalCardUserScrollGesturePolicy policy = new VerticalCardUserScrollGesturePolicy();
        policy.onTouchDown(420);
        assertThat(policy.onTouchMove(400), is(true));
        policy.onTouchEnd();
        policy.onTouchDown(400);
        assertThat(policy.onTouchMove(380), is(true));
    }

    @Test
    void movesOutsideAnActiveTouchDoNotCancelPin() {
        VerticalCardUserScrollGesturePolicy policy = new VerticalCardUserScrollGesturePolicy();
        assertThat(policy.onTouchMove(100), is(false));
        policy.onTouchDown(100);
        policy.onTouchEnd();
        assertThat(policy.onTouchMove(80), is(false));
    }
}
