package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.NotificationHistoryActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.dataprovider.simpleprovider.SimpleProvider;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.pojo.NotificationHistorySearchPojo;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.searcher.QuerySearcher;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

/** Actual button taps, real provider execution and the destination Activity's filtered records. */
public final class SearchQuickActions188RegressionTest {
    private Context app;
    private ActivityScenario<MainActivity> scenario;
    private Activity archiveActivity;
    private Map<String, Object> providers;
    private final AtomicInteger fullScans = new AtomicInteger();
    private final String databaseId = "setting://database-only188";

    @Before public void setUp() throws Exception {
        app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HistoryTestSupport188.configure(app);
        scenario = ActivityScenario.launch(MainActivity.class);
        HistoryTestSupport188.awaitUi(scenario, "Providers loaded", activity ->
                KissApplication.getApplication(activity).getDataHandler().isAllProvidersLoaded());
        scenario.onActivity(activity -> {
            try {
                DataHandler data = KissApplication.getApplication(activity).getDataHandler();
                providers = (Map<String, Object>) HistoryTestSupport188.field(data, "providers");
                Class<?> entryClass = Class.forName("fr.neamar.kiss.DataHandler$ProviderEntry");
                Constructor<?> constructor = entryClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object entry = constructor.newInstance();
                Field provider = entryClass.getDeclaredField("provider");
                provider.setAccessible(true);
                provider.set(entry, new SimpleProvider<SettingPojo>() {
                    @Override public void requestResults(String query, Searcher searcher) {
                        fullScans.incrementAndGet();
                        if (query.contains("database188")) {
                            SettingPojo result = row(databaseId, "Database only database188");
                            searcher.addResult(result);
                        }
                    }
                    @Override public List<SettingPojo> getPojos() { return Collections.emptyList(); }
                });
                providers.put("test-quick-actions188", entry);
                installSaved(activity);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }

    @After public void tearDown() throws Exception {
        if (archiveActivity != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> archiveActivity.finish());
        }
        if (scenario != null) {
            scenario.onActivity(activity -> {
                SearchHandler.getInstance().cancelSearch();
                if (providers != null) providers.remove("test-quick-actions188");
            });
            scenario.close();
        }
    }

    @Test public void savedPreviewIsImmediateAndDatabaseButtonExecutesOnlyOnceBeforeFiveSeconds()
            throws Exception {
        long began = SystemClock.elapsedRealtime();
        scenario.onActivity(activity -> {
            activity.searchEditText.setText("database188");
            assertEquals("Saved window is visible in the same UI turn", 1, activity.adapter.getCount());
            assertEquals("setting://saved188", activity.adapter.getItem(0).getPojoId());
            assertEquals(0, fullScans.get());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.searchDatabaseNow).getVisibility());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.searchNotificationHistory).getVisibility());
            assertTrue(activity.findViewById(R.id.searchDatabaseNow).performClick());
            assertNull(HistoryTestSupport188.field(SearchHandler.getInstance(), "pendingQuery"));
            assertFalse("Second tap cannot start another provider scan",
                    SearchHandler.getInstance().startPendingQueryNow(activity));
        });
        HistoryTestSupport188.awaitUi(scenario, "Database-only result reached the adapter",
                activity -> HistoryTestSupport188.hasRow(activity, databaseId));
        assertTrue("Explicit tap bypasses the five-second deadline",
                SystemClock.elapsedRealtime() - began < 4500L);
        assertEquals(1, fullScans.get());
        scenario.onActivity(activity -> {
            assertEquals(View.GONE, activity.findViewById(R.id.searchDatabaseNow).getVisibility());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.searchNotificationHistory).getVisibility());
        });
    }

    @Test public void normalTimerAndNotificationRefreshKeepTheOriginalFiveSecondDeadline()
            throws Exception {
        final Runnable[] original = new Runnable[1];
        final long[] began = new long[1];
        scenario.onActivity(activity -> {
            activity.searchEditText.setText("database188 timer");
            began[0] = SystemClock.elapsedRealtime();
            original[0] = (Runnable) HistoryTestSupport188.field(SearchHandler.getInstance(), "pendingQuery");
            activity.sendBroadcast(MainActivity.internalBroadcast(activity, MainActivity.LOAD_OVER)
                    .putExtra(MainActivity.EXTRA_NOTIFICATION_TIMELINE_ID, "notification://refresh188")
                    .putExtra(MainActivity.EXTRA_NOTIFICATION_POSTED, true));
        });
        Thread.sleep(1200L);
        scenario.onActivity(activity -> {
            assertSame("Provider refresh neither replaces nor runs the pending deadline", original[0],
                    HistoryTestSupport188.field(SearchHandler.getInstance(), "pendingQuery"));
            assertEquals(0, fullScans.get());
        });
        PowerTestSupport187.await("Normal full search still starts after idle", () -> fullScans.get() == 1);
        long elapsed = SystemClock.elapsedRealtime() - began[0];
        assertTrue("No provider scan before five seconds: " + elapsed, elapsed >= 4900L);
        assertTrue("Refresh cannot postpone the deadline indefinitely: " + elapsed, elapsed < 7500L);
        Thread.sleep(300L);
        assertEquals(1, fullScans.get());
    }

    @Test public void changedTextClearAndPauseCancelOldCallbacksAndSqlWork() throws Exception {
        final Runnable[] old = new Runnable[1];
        scenario.onActivity(activity -> {
            activity.searchEditText.setText("old database188");
            old[0] = (Runnable) HistoryTestSupport188.field(SearchHandler.getInstance(), "pendingQuery");
            activity.searchEditText.setText("new database188");
            Object latest = HistoryTestSupport188.field(SearchHandler.getInstance(), "pendingQuery");
            old[0].run();
            assertSame(latest, HistoryTestSupport188.field(SearchHandler.getInstance(), "pendingQuery"));
            activity.searchEditText.setText("");
            old[0].run();
            assertEquals(View.GONE, activity.findViewById(R.id.searchQuickActions).getVisibility());
            assertNull(HistoryTestSupport188.field(SearchHandler.getInstance(), "pendingQuery"));
            assertEquals(0, fullScans.get());
            activity.searchEditText.setText("paused database188");
        });
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);
        assertNull(HistoryTestSupport188.field(SearchHandler.getInstance(), "pendingQuery"));
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
        scenario.onActivity(activity -> {
            assertNotNull(HistoryTestSupport188.field(SearchHandler.getInstance(), "pendingQuery"));
            assertTrue(SearchHandler.getInstance().startPendingQueryNow(activity));
            Object running = HistoryTestSupport188.field(SearchHandler.getInstance(), "runningSearch");
            assertTrue(running instanceof QuerySearcher);
            SearchHandler.getInstance().cancelSearch();
            assertTrue(((QuerySearcher) running).getDatabaseCancellationSignal().isCanceled());
            assertEquals(View.GONE, activity.findViewById(R.id.searchQuickActions).getVisibility());
        });
    }

    @Test public void notificationButtonOpensCurrentQueryIncludingPermanentArchiveWithCaptureOff()
            throws Exception {
        long time = System.currentTimeMillis();
        for (boolean permanent : new boolean[]{false, true}) {
            assertTrue(SmartStateStore.saveNotification(app, "notification://archive-button188/" + permanent,
                    "test.search188", "Archive fixture188", "needle188", "Exact saved needle188",
                    time + (permanent ? 1 : 0), permanent, "", -1L, "", "", ""));
        }
        assertTrue(SmartStateStore.saveNotification(app, "notification://archive-unrelated188",
                "test.search188", "Archive fixture188", "Unrelated", "Unrelated saved text",
                time + 2, false, "", -1L, "", "", ""));
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putBoolean("enable-notification-history", false).commit();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                NotificationHistoryActivity.class.getName(), null, false);
        try {
            scenario.onActivity(activity -> {
                activity.searchEditText.setText("needle188");
                assertEquals(0, fullScans.get());
                assertTrue(activity.findViewById(R.id.searchNotificationHistory).performClick());
                assertNull(HistoryTestSupport188.field(SearchHandler.getInstance(), "pendingQuery"));
            });
            archiveActivity = monitor.waitForActivityWithTimeout(8000L);
            assertNotNull("Actual Notification History Activity opened", archiveActivity);
            assertEquals("needle188", archiveActivity.getIntent()
                    .getStringExtra(NotificationHistoryActivity.EXTRA_SEARCH_QUERY));
            assertTrue(archiveActivity.getIntent().getBooleanExtra(NotificationHistoryActivity.EXTRA_SEARCH_ALL, false));
            PowerTestSupport187.await("Archive query displayed both regular and permanent matches", () -> {
                final AtomicInteger count = new AtomicInteger();
                instrumentation.runOnMainSync(() -> count.set(
                        ((List<?>) HistoryTestSupport188.field(archiveActivity, "records")).size()));
                return count.get() == 2;
            });
            assertEquals("Scoped button never starts the whole-launcher provider scan", 0, fullScans.get());
            HistoryTestSupport188.screenshot(app, "notification-history-query188");
        } finally { instrumentation.removeMonitor(monitor); }
    }

    @Test public void bothButtonsStayAboveKeyboardAndDockAcrossListCardsAndWheel() throws Exception {
        for (String mode : new String[]{"vertical", "vertical_cards", "wheel_3d"}) {
            PreferenceManager.getDefaultSharedPreferences(app).edit()
                    .putString("smart-history-layout", mode).commit();
            scenario.recreate();
            scenario.onActivity(activity -> {
                installSaved(activity);
                activity.searchEditText.requestFocus();
                activity.searchEditText.showBuiltInKeyboard();
                activity.searchEditText.setText("database188");
            });
            HistoryTestSupport188.awaitUi(scenario, "Both action buttons are laid out", activity ->
                    activity.findViewById(R.id.searchQuickActions).getHeight() > 0);
            scenario.onActivity(activity -> {
                Rect database = new Rect(), notifications = new Rect(), bar = new Rect();
                assertTrue(activity.findViewById(R.id.searchDatabaseNow).getGlobalVisibleRect(database));
                assertTrue(activity.findViewById(R.id.searchNotificationHistory).getGlobalVisibleRect(notifications));
                assertTrue(activity.findViewById(R.id.searchEditLayout).getGlobalVisibleRect(bar));
                assertFalse("Actions must not overlap each other", Rect.intersects(database, notifications));
                assertTrue("Search field and keyboard remain unobstructed", database.bottom <= bar.top
                        && notifications.bottom <= bar.top);
            });
            HistoryTestSupport188.screenshot(app, "search-actions188-" + mode);
        }
    }

    private static SettingPojo row(String id, String name) {
        SettingPojo row = new SettingPojo(id, "", -1);
        row.setName(name);
        return row;
    }

    private static void installSaved(MainActivity activity) {
        SearchHandler search = SearchHandler.getInstance();
        search.search(Searcher.Type.HISTORY, activity, null, false);
        search.cancelSearch();
        activity.adapter.clear();
        activity.adapter.updateWithPojos(activity,
                Collections.singletonList(row("setting://saved188", "Saved database188")), false, "<history>");
    }
}
