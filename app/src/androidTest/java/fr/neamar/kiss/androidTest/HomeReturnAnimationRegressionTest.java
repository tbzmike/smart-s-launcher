package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.ui.SmartAnimationEngine;

/** Regression for the previously transparent Home root and black Vertical Cards frame. */
public final class HomeReturnAnimationRegressionTest {
    @Test public void homeNeverDrawsTransparentRootWhenReturningToVerticalCards() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        String originalMode = prefs.getString("smart-history-layout", "vertical");
        String originalEnter = prefs.getString("smart-animation-window-enter", "fade");
        String originalExit = prefs.getString("smart-animation-window-exit", "fade");
        boolean originalAnimations = prefs.getBoolean("smart-animations-enabled", true);
        try {
            assertTrue(prefs.edit()
                    .putString("smart-history-layout", "vertical_cards")
                    .putString("smart-animation-window-enter", "whirl")
                    .putString("smart-animation-window-exit", "rotate")
                    .putBoolean("smart-animations-enabled", true).commit());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                AtomicInteger invisibleFrames = new AtomicInteger();
                View[] rootRef = new View[1];
                scenario.onActivity(activity -> {
                    View root = activity.findViewById(android.R.id.content);
                    rootRef[0] = root;
                    assertNotNull(root);
                    root.getViewTreeObserver().addOnPreDrawListener(() -> {
                        if (root.getAlpha() < .999f) invisibleFrames.incrementAndGet();
                        return true;
                    });
                });
                for (int i = 0; i < 3; i++) {
                    scenario.moveToState(Lifecycle.State.STARTED);
                    scenario.moveToState(Lifecycle.State.RESUMED);
                    scenario.onActivity(activity -> {
                        View root = rootRef[0];
                        assertEquals("Home root cannot remain transparent", 1f, root.getAlpha(), .001f);
                        try {
                            Field forwarders = MainActivity.class.getDeclaredField("forwarderManager");
                            forwarders.setAccessible(true);
                            Object manager = forwarders.get(activity);
                            Field cards = manager.getClass().getDeclaredField("smartCardListForwarder");
                            cards.setAccessible(true);
                            Object cardForwarder = cards.get(manager);
                            Field scroller = cardForwarder.getClass().getDeclaredField("scroller");
                            scroller.setAccessible(true);
                            ScrollView scroll = (ScrollView) scroller.get(cardForwarder);
                            assertNotNull(scroll);
                            assertEquals(View.VISIBLE, scroll.getVisibility());
                            assertEquals("Cards must not be faded in over black", 1f,
                                    scroll.getAlpha(), .001f);
                        } catch (ReflectiveOperationException error) {
                            throw new AssertionError(error);
                        }
                    });
                }
                assertEquals("No transparent Home frames are allowed", 0, invisibleFrames.get());
            }
        } finally {
            prefs.edit().putString("smart-history-layout", originalMode)
                    .putString("smart-animation-window-enter", originalEnter)
                    .putString("smart-animation-window-exit", originalExit)
                    .putBoolean("smart-animations-enabled", originalAnimations).commit();
        }
    }
}
