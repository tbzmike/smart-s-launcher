package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import java.lang.reflect.Field;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

/** Ensure a rapidly changing query never executes a full provider scan prematurely. */
public final class HistoryFirstSearch181RegressionTest {
    @Test public void typingOnlySchedulesAFullSearchAfterIdle() throws Exception {
        final Field pending = SearchHandler.class.getDeclaredField("pendingQuery");
        final Field running = SearchHandler.class.getDeclaredField("runningSearch");
        pending.setAccessible(true);
        running.setAccessible(true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                SearchHandler search = SearchHandler.getInstance();
                search.search(Searcher.Type.QUERY, activity, "firsthistoryonly181", false);
                try {
                    assertNotNull("Heavy search must be queued, not immediately started", pending.get(search));
                    assertNull("Provider search must not be started while typing", running.get(search));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            });
            Thread.sleep(250);
            scenario.onActivity(activity -> {
                SearchHandler search = SearchHandler.getInstance();
                search.search(Searcher.Type.QUERY, activity, "secondhistoryonly181", false);
                try {
                    assertNotNull("New query replaces timer", pending.get(search));
                    assertNull("A previous generation cannot start early", running.get(search));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            });
            Thread.sleep(1_200);
            scenario.onActivity(activity -> {
                SearchHandler search = SearchHandler.getInstance();
                try {
                    assertNotNull("Full search must still be delayed until five seconds idle",
                            pending.get(search));
                    assertNull("No full search during the five-second wait", running.get(search));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
                search.cancelSearch();
                try {
                    assertNull("Cancellation removes pending deep search", pending.get(search));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            });
        }
    }
}
