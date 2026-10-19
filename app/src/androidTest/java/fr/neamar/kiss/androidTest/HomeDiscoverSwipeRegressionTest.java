package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.ui.HomeDiscoverSwipeGesture;
import fr.neamar.kiss.ui.SettingsSearchIndex;

/** Actual Android gesture and settings wiring across all three Home layouts. */
public final class HomeDiscoverSwipeRegressionTest {
    private static void swipe(MainActivity activity, float x1, float y1, float x2, float y2) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                x1, y1, 0);
        MotionEvent move = MotionEvent.obtain(now, now + 130, MotionEvent.ACTION_MOVE,
                (x1 + x2) / 2f, (y1 + y2) / 2f, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 260, MotionEvent.ACTION_UP,
                x2, y2, 0);
        try {
            activity.dispatchTouchEvent(down);
            activity.dispatchTouchEvent(move);
            activity.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            move.recycle();
            up.recycle();
        }
    }

    @Test public void recognizerRequiresRightHorizontalSingleFingerGesture() {
        HomeDiscoverSwipeGesture detector = new HomeDiscoverSwipeGesture(2f, 12);
        assertFalse("No gesture without DOWN", detector.observe(
                MotionEvent.obtain(5, 50, MotionEvent.ACTION_UP, 320, 100, 0), true));

        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 100, 200, 0);
        MotionEvent end = MotionEvent.obtain(t, t + 180, MotionEvent.ACTION_UP, 340, 211, 0);
        try {
            assertFalse(detector.observe(down, true));
            assertTrue("A clear Home swipe right should open Discover",
                    detector.observe(end, true));
            assertFalse("Must not repeat a consumed gesture", detector.observe(end, true));

            detector.observe(down, true);
            assertFalse("Vertical scrolling must remain native",
                    detector.observe(MotionEvent.obtain(t, t + 180,
                            MotionEvent.ACTION_UP, 145, 480, 0), true));
            detector.observe(down, false);
            assertFalse("The disabled switch must prevent Discover",
                    detector.observe(end, false));
        } finally {
            down.recycle();
            end.recycle();
        }
    }

    @Test public void discoverToggleIsSearchableAndOperatesLiveAcrossThreeHistoryStyles()
            throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Setting must be findable by its name", SettingsSearchIndex.search(ctx,
                "google discover").stream().anyMatch(entry ->
                        HomeDiscoverSwipeGesture.PREF_KEY.equals(entry.key)));
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        boolean oldEnabled = prefs.getBoolean(HomeDiscoverSwipeGesture.PREF_KEY, false);
        String oldStyle = prefs.getString("smart-history-layout", "vertical");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicInteger launches = new AtomicInteger();
            scenario.onActivity(activity -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("googleDiscoverLaunch");
                    field.setAccessible(true);
                    field.set(activity, (Runnable) launches::incrementAndGet);
                    activity.searchEditText.setText("");
                    activity.displayKissBar(false);
                    activity.showHistory();
                } catch (Exception error) { throw new AssertionError(error); }
            });
            for (String mode : new String[]{"vertical", "vertical_cards", "wheel_3d"}) {
                assertTrue(prefs.edit().putString("smart-history-layout", mode)
                        .putBoolean(HomeDiscoverSwipeGesture.PREF_KEY, true).commit());
                scenario.onActivity(activity -> {
                    int before = launches.get();
                    swipe(activity, 70, 450, 380, 465);
                    assertEquals("Home right-swipe must trigger in " + mode, before + 1,
                            launches.get());
                    swipe(activity, 380, 455, 70, 460);
                    swipe(activity, 90, 350, 120, 700);
                    assertEquals("Left swipe and vertical scroll must not open Discover",
                            before + 1, launches.get());
                    prefs.edit().putBoolean(HomeDiscoverSwipeGesture.PREF_KEY, false).commit();
                    swipe(activity, 70, 450, 380, 465);
                    assertEquals("Live toggle OFF must immediately restore normal gestures",
                            before + 1, launches.get());
                });
            }
        } finally {
            prefs.edit().putBoolean(HomeDiscoverSwipeGesture.PREF_KEY, oldEnabled)
                    .putString("smart-history-layout", oldStyle).commit();
        }
    }
}
