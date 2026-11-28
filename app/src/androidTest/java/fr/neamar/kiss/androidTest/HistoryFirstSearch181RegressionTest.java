package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import java.lang.reflect.Field;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.searcher.SearchHandler;

/** Ensure a rapidly changing query never executes a full provider scan prematurely. */
public final class HistoryFirstSearch181RegressionTest {
    @Test public void untappedProviderHitIsSavedAndItsFullNameWorksBeforeAnotherDeepSearch() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            MainActivity[] holder = new MainActivity[1];
            scenario.onActivity(a -> holder[0] = a);
            MainActivity a = holder[0];
            fr.neamar.kiss.DataHandler data = fr.neamar.kiss.KissApplication.getApplication(a).getDataHandler();
            long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
            while (!data.isAllProvidersLoaded() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            org.junit.Assert.assertTrue("Providers loaded",data.isAllProvidersLoaded());
            fr.neamar.kiss.pojo.AppPojo target = null;
            for (fr.neamar.kiss.pojo.AppPojo app : data.getApplications()) {
                if (!app.isDisabled() && !app.isExcluded() && app.getName().matches("[A-Za-z]{4,}")) { target=app; break; }
            }
            assertNotNull("Installed, searchable application",target);
            String name=target.getName(), id=target.id, prefix=name.substring(0,2).toLowerCase(java.util.Locale.ROOT);
            fr.neamar.kiss.db.DBHelper.clearHistory(a);
            scenario.onActivity(owner -> owner.searchEditText.setText(prefix));
            Thread.sleep(1500);
            assertEquals("A new query must not be persisted before the delay",0,
                    fr.neamar.kiss.db.DBHelper.getRecentSearchRecall(a,1).size());
            deadline=android.os.SystemClock.elapsedRealtime()+30000;
            boolean found=false;
            while(!found && android.os.SystemClock.elapsedRealtime()<deadline) {
                for(fr.neamar.kiss.db.DBHelper.SearchRecallRow row:fr.neamar.kiss.db.DBHelper.getRecentSearchRecall(a,10000))
                    if(prefix.equals(row.query) && id.equals(row.record)) found=true;
                if(!found) Thread.sleep(100);
            }
            org.junit.Assert.assertTrue("Full scan persisted the untapped app",found);
            fr.neamar.kiss.searcher.SearchRecallCache.invalidate();
            fr.neamar.kiss.searcher.SearchRecallCache.warm(a,data,10003);
            scenario.onActivity(owner -> {
                owner.searchEditText.setText(name);
                boolean visible=false;
                for(int i=0;i<owner.adapter.getCount();i++) if(id.equals(owner.adapter.getItem(i).getPojoId())) visible=true;
                org.junit.Assert.assertTrue("Rehydrated name matches before the second full scan",visible);
                SearchHandler.getInstance().cancelSearch();
            });
            fr.neamar.kiss.db.DBHelper.clearHistory(a);
        }
    }

    @Test public void typingOnlySchedulesAFullSearchAfterIdle() throws Exception {
        final Field pending = SearchHandler.class.getDeclaredField("pendingQuery");
        final Field running = SearchHandler.class.getDeclaredField("runningSearch");
        pending.setAccessible(true);
        running.setAccessible(true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                SearchHandler search = SearchHandler.getInstance();
                activity.searchEditText.setText("firsthistoryonly181");
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
                activity.searchEditText.setText("secondhistoryonly181");
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
