package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.pojo.ContactsPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

/** Real adapter assertions in the same UI turn: waiting for the five-second scan cannot pass. */
public final class InstantHistory185RegressionTest {
    private static final String[] KEYS = {"number-of-history-results", "number-of-search-results",
            "exclude-favorites-history", "exclude-favorites-apps"};

    @Test public void instantWindowUses803SavedRowsAndLiveLimitsIncludingContactsAndMessages() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
                Map<String, ?> original = prefs.getAll();
                SearchHandler search = SearchHandler.getInstance();
                try {
                    configure(prefs, "803", "155");
                    List<Pojo> rows = new ArrayList<>();
                    for (int i = 0; i < 804; i++) rows.add(row(i, "Filler185 " + i));
                    rows.set(0, row(0, "Outside803Window185"));
                    rows.set(1, row(1, "Inside803Window185"));
                    rows.set(400, row(400, "Outside403Window185"));
                    rows.set(401, row(401, "Inside403Window185"));
                    ContactsPojo contact = new ContactsPojo("contact://instant185", "lookup185",
                            185L, null, false, false);
                    contact.setName("RecentContact185");
                    rows.set(802, contact);
                    NotificationPojo message = new NotificationPojo("notification://instant185",
                            "test.message185", "Messages185", "group185", "exact185", 1,
                            "RecentSender185", "Recently searched message185", 185L);
                    rows.set(803, message);
                    installSavedRows(activity, rows);

                    assertImmediate(activity, "Inside803Window185", "setting://instant185/1");
                    assertImmediate(activity, "RecentContact185", contact.id);
                    assertImmediate(activity, "recently searched message185", message.id);
                    assertEmptyImmediately(activity, "Outside803Window185");

                    prefs.edit().putString("number-of-history-results", "403").commit();
                    assertEmptyImmediately(activity, "Outside403Window185");
                    assertImmediate(activity, "Inside403Window185", "setting://instant185/401");
                    prefs.edit().putString("number-of-search-results", "2").commit();
                    search.search(Searcher.Type.QUERY, activity, "Filler185", false);
                    assertEquals("Search output cap is independent of the saved input window",
                            2, activity.adapter.getCount());

                    prefs.edit().putString("number-of-history-results", "0").commit();
                    assertEmptyImmediately(activity, "RecentContact185");
                } finally {
                    search.cancelSearch();
                    restore(prefs, original);
                }
            });
        }
    }

    @Test public void justLaunchedTaggedTargetIsImmediateAndRapidQueriesNeverReuseOldMatches() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
                Map<String, ?> original = prefs.getAll();
                SearchHandler search = SearchHandler.getInstance();
                try {
                    configure(prefs, "803", "155");
                    List<Pojo> rows = new ArrayList<>();
                    rows.add(row(10, "Old saved target185"));
                    installSavedRows(activity, rows);
                    SettingPojo launched = row(11, "Just launched target185");
                    launched.setTags("savedtag185");
                    search.rememberLaunchedResult(activity, Result.fromPojo(activity, launched));
                    assertImmediate(activity, "savedtag185", launched.id);
                    assertEmptyImmediately(activity, "nomatching185");
                    assertImmediate(activity, "just launched target185", launched.id);
                } finally {
                    search.cancelSearch();
                    restore(prefs, original);
                }
            });
        }
    }

    private static SettingPojo row(int index, String name) {
        SettingPojo pojo = new SettingPojo("setting://instant185/" + index, "", -1);
        pojo.setName(name);
        return pojo;
    }

    private static void installSavedRows(MainActivity activity, List<Pojo> rows) {
        SearchHandler search = SearchHandler.getInstance();
        search.search(Searcher.Type.HISTORY, activity, null, false);
        search.cancelSearch();
        activity.adapter.updateWithPojos(activity, rows, false, "<history>");
    }

    private static void assertImmediate(MainActivity activity, String query, String expectedId) {
        long started = SystemClock.elapsedRealtime();
        SearchHandler.getInstance().search(Searcher.Type.QUERY, activity, query, false);
        assertEquals("Saved match is rendered before search() returns", 1, activity.adapter.getCount());
        assertEquals(expectedId, activity.adapter.getItem(0).getPojoId());
        assertOnlyDelayedFullSearch();
        System.out.println("INSTANT_HISTORY_185 " + query + " "
                + (SystemClock.elapsedRealtime() - started) + "ms");
    }

    private static void assertEmptyImmediately(MainActivity activity, String query) {
        SearchHandler.getInstance().search(Searcher.Type.QUERY, activity, query, false);
        assertEquals("No older/full-database or previous-query rows may leak into stage one",
                0, activity.adapter.getCount());
        assertOnlyDelayedFullSearch();
    }

    private static void assertOnlyDelayedFullSearch() {
        try {
            Field pending = SearchHandler.class.getDeclaredField("pendingQuery");
            Field running = SearchHandler.class.getDeclaredField("runningSearch");
            pending.setAccessible(true);
            running.setAccessible(true);
            assertNotNull("Second stage is scheduled", pending.get(SearchHandler.getInstance()));
            assertNull("No full scan started", running.get(SearchHandler.getInstance()));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void configure(SharedPreferences prefs, String history, String search) {
        prefs.edit().putString(KEYS[0], history).putString(KEYS[1], search)
                .putBoolean(KEYS[2], false).putBoolean(KEYS[3], false).commit();
    }

    private static void restore(SharedPreferences prefs, Map<String, ?> original) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : KEYS) {
            Object value = original.get(key);
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else editor.remove(key);
        }
        editor.commit();
    }
}
