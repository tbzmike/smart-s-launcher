package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.ui.HistoryDateNavigator;
import fr.neamar.kiss.ui.HistoryTimeRange;

/** Verifies real timestamp chips in the live ListView, not only formatter functions. */
public final class VisibleHistoryTime199IntegrationTest {
    private final String[] displayed = new String[3];
    private final String[] dates = new String[2];

    private static TextView field(HistoryDateNavigator nav, String name) {
        try {
            Field f = HistoryDateNavigator.class.getDeclaredField(name);
            f.setAccessible(true);
            return (TextView) f.get(nav);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static HistoryDateNavigator navigator(MainActivity activity) {
        try {
            Field f = MainActivity.class.getDeclaredField("historyDateNavigator");
            f.setAccessible(true);
            return (HistoryDateNavigator) f.get(activity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test public void dateAndTimeAreAdjacentAndUpdateFromScrolledVisibleRows() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        String oldLayout = prefs.getString("smart-history-layout", "vertical");
        String oldFormat = prefs.getString(HistoryTimeRange.PREF_FORMAT,
                HistoryTimeRange.FORMAT_24H_H);
        boolean oldHidden = prefs.getBoolean("history-hide", false);
        try {
            assertTrue(prefs.edit().putString("smart-history-layout", "vertical")
                    .putString(HistoryTimeRange.PREF_FORMAT, HistoryTimeRange.FORMAT_24H_H)
                    .putBoolean("history-hide", false).commit());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                BaselineBehaviorTest.await(scenario,
                        activity -> KissApplication.getApplication(activity)
                                .getDataHandler().isAllProvidersLoaded(),
                        "History time test providers ready");
                BaselineBehaviorTest.awaitHistory(scenario);
                scenario.onActivity(activity -> {
                    SearchHandler.getInstance().cancelSearch();
                    Calendar today = Calendar.getInstance();
                    today.set(Calendar.HOUR_OF_DAY, 10);
                    today.set(Calendar.MINUTE, 0);
                    today.set(Calendar.SECOND, 0);
                    today.set(Calendar.MILLISECOND, 0);
                    List<Result<?>> items = new ArrayList<>();
                    for (int i = 0; i < 24; i++) {
                        NotificationPojo p = new NotificationPojo(
                                "notification://time199-" + i,
                                activity.getPackageName(), "Time test " + i,
                                "", "time199-" + i, 1,
                                "History event " + i, "Row timestamp", today.getTimeInMillis()
                                        + i * 60_000L);
                        items.add(Result.fromPojo(activity, p));
                    }
                    // The launcher deliberately anchors new History to the newest item.
                    // Disable only that test-time transcript behaviour so two distinct
                    // viewport positions can be measured deterministically.
                    activity.list.setTranscriptMode(
                            android.widget.AbsListView.TRANSCRIPT_MODE_DISABLED);
                    activity.adapter.updateResults(activity, items, false, "");
                    activity.list.post(() -> activity.list.setSelectionFromTop(0, 0));
                });
                Thread.sleep(450);
                scenario.onActivity(activity -> {
                    HistoryDateNavigator nav = navigator(activity);
                    assertNotNull(nav);
                    TextView time = field(nav, "timeRangeLabel");
                    TextView date = field(nav, "sectionLabel");
                    assertEquals(View.VISIBLE, time.getVisibility());
                    assertEquals(View.VISIBLE, date.getVisibility());
                    assertEquals("Today", date.getText().toString());
                    assertTrue("First measurement must actually show the older viewport",
                            activity.list.getFirstVisiblePosition() <= 3);
                    displayed[0] = time.getText().toString();
                    dates[0] = date.getText().toString();
                    assertTrue("First visible History events must show their 10 AM timestamps",
                            displayed[0].startsWith("10h"));
                    assertTrue("Navigator must keep date and time in a shared header",
                            time.getParent() == date.getParent());
                    activity.list.setSelectionFromTop(18, 0);
                });
                Thread.sleep(450);
                scenario.onActivity(activity -> {
                    HistoryDateNavigator nav = navigator(activity);
                    displayed[1] = field(nav, "timeRangeLabel").getText().toString();
                    dates[1] = field(nav, "sectionLabel").getText().toString();
                    assertEquals("Today", dates[1]);
                    assertTrue("Second measurement must be taken from a different scroll position",
                            activity.list.getFirstVisiblePosition() >= 12);
                    assertTrue(displayed[1].startsWith("10h"));
                    assertNotEquals("Time must follow viewport instead of staying at first rows",
                            displayed[0], displayed[1]);
                    assertTrue(prefs.edit().putString(HistoryTimeRange.PREF_FORMAT,
                            HistoryTimeRange.FORMAT_12H).commit());
                    nav.onSurfaceChanged();
                });
                Thread.sleep(200);
                scenario.onActivity(activity -> {
                    displayed[2] = field(navigator(activity), "timeRangeLabel").getText().toString();
                    assertFalse("12-hour time must not keep the previous 24h text",
                            displayed[2].contains("10h"));
                    assertTrue("Settings change must update existing time indicator",
                            displayed[2].contains("AM"));
                });
            }
        } finally {
            assertTrue(prefs.edit().putString("smart-history-layout", oldLayout)
                    .putString(HistoryTimeRange.PREF_FORMAT, oldFormat)
                    .putBoolean("history-hide", oldHidden).commit());
        }
    }
}
