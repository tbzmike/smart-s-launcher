package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.NotificationTimelineStore;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.notification.NotificationTimelineState;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.LauncherScrollWorkGate;
import fr.neamar.kiss.utils.SavedNotificationDestinationResolver;

public final class NotificationRetention188RegressionTest {
    private Context app;
    private ActivityScenario<MainActivity> scenario;

    @Before public void setUp() throws Exception {
        app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HistoryTestSupport188.configure(app);
        PowerTestSupport187.connect(app);
        PowerTestSupport187.control("notify-clear");
        scenario = ActivityScenario.launch(MainActivity.class);
        HistoryTestSupport188.awaitUi(scenario, "History providers ready", activity ->
                KissApplication.getApplication(activity).getDataHandler().isAllProvidersLoaded());
    }

    @After public void tearDown() throws Exception {
        LauncherScrollWorkGate.setScrolling(false);
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putBoolean("freeze-history", false).putBoolean("enable-notification-history", true).commit();
        scenario.onActivity(activity -> {
            SearchHandler.getInstance().onHistoryScrollIdle(activity);
            SearchHandler.getInstance().cancelSearch();
        });
        PowerTestSupport187.control("notify-clear");
        scenario.close();
    }

    @Test public void receivedNotificationRemainsOnHomeAfterRemovalAndListenerReconnect() throws Exception {
        NotificationHistoryRecord record = post("notify-history-one");
        awaitRow(record.notificationId, true);
        int count = HistoryTestSupport188.historyCount(app, record.notificationId);
        PowerTestSupport187.control("notify-clear");
        PowerTestSupport187.await("Android notification actually removed", () ->
                !NotificationListener.isNotificationActive(app, record.notificationId));
        refreshHome();
        awaitRow(record.notificationId, true);
        reconnect();
        refreshHome();
        awaitRow(record.notificationId, true);
        assertEquals("Reconnect cannot duplicate receipt history", count,
                HistoryTestSupport188.historyCount(app, record.notificationId));
        assertNotNull(NotificationTimelineStore.findExact(app, record.notificationId, record.postTime));
        HistoryTestSupport188.screenshot(app, "received-then-dismissed-history188");
    }

    @Test public void receiptCommitsDuringScrollEvenWhenOptionalRouteWorkerIsBlocked() throws Exception {
        java.lang.reflect.Field f = NotificationListener.class.getDeclaredField("RECONCILE_EXECUTOR");
        f.setAccessible(true);
        ExecutorService optional = (ExecutorService) f.get(null);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        optional.execute(() -> {
            entered.countDown();
            try { release.await(12, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            scenario.onActivity(activity -> {
                SearchHandler.getInstance().onHistoryScrollStarted(activity);
                LauncherScrollWorkGate.setScrolling(true);
            });
            NotificationHistoryRecord record = post("notify-history-rapid");
            assertTrue("Metadata is journaled while optional work is still deferred",
                    HistoryTestSupport188.historyCount(app, record.notificationId) >= 1);
            assertNotNull(record.pendingIntentToken);
            assertFalse("Exact Android route is already durable", record.pendingIntentToken.isEmpty());
            PowerTestSupport187.await("The fixture really removed the short-lived notification", () ->
                    !NotificationListener.isNotificationActive(app, record.notificationId));
            assertNotNull(NotificationTimelineStore.findExact(app, record.notificationId, record.postTime));
            release.countDown();
            LauncherScrollWorkGate.setScrolling(false);
            scenario.onActivity(activity -> SearchHandler.getInstance().onHistoryScrollIdle(activity));
            refreshHome();
            awaitRow(record.notificationId, true);
        } finally { release.countDown(); LauncherScrollWorkGate.setScrolling(false); }
    }

    @Test public void dismissedSavedNotificationStillOpensTheExactPublisherDestination() throws Exception {
        NotificationHistoryRecord record = post("notify-history-one");
        PowerTestSupport187.control("notify-clear");
        PowerTestSupport187.await("Native post removed", () ->
                !NotificationListener.isNotificationActive(app, record.notificationId));
        LinkedBlockingQueue<Intent> hits = new LinkedBlockingQueue<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { hits.offer(intent); }
        };
        ContextCompat.registerReceiver(app, receiver,
                new IntentFilter("com.tbzmike.shortcutfixture.HIT"), ContextCompat.RECEIVER_EXPORTED);
        try {
            scenario.onActivity(activity -> assertTrue("Exact saved route accepted",
                    SavedNotificationDestinationResolver.openExact(activity, record)));
            Intent hit = hits.poll(8, TimeUnit.SECONDS);
            assertNotNull("Actual publisher destination opened", hit);
            assertEquals("notification188", hit.getStringExtra("destination"));
            assertEquals("fixture://notification/received188", hit.getStringExtra("data"));
        } finally { app.unregisterReceiver(receiver); }
    }

    @Test public void explicitRemovalFreezeAndHistoryOffRemainRespected() throws Exception {
        NotificationHistoryRecord first = post("notify-history-one");
        NotificationTimelineState.hideFromHistory(app, first.notificationId, first.postTime);
        DBHelper.removeFromHistory(app, first.notificationId);
        reconnect();
        refreshHome();
        awaitRow(first.notificationId, false);
        assertEquals(0, HistoryTestSupport188.historyCount(app, first.notificationId));
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("freeze-history", true).commit();
        PowerTestSupport187.control("notify-clear");
        Thread.sleep(30L);
        NotificationHistoryRecord frozen = post("notify-history-fresh");
        assertTrue(frozen.postTime > first.postTime);
        assertEquals(0, HistoryTestSupport188.historyCount(app, frozen.notificationId));
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("freeze-history", false).commit();
        PowerTestSupport187.control("notify-clear");
        Thread.sleep(30L);
        NotificationHistoryRecord next = post("notify-history-one");
        assertTrue(next.postTime > frozen.postTime);
        assertEquals(1, HistoryTestSupport188.historyCount(app, next.notificationId));
        int saved = SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE, null, 200).size();
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putBoolean("enable-notification-history", false).commit();
        PowerTestSupport187.control("notify-clear");
        PowerTestSupport187.control("notify-history-fresh");
        Thread.sleep(500L);
        assertEquals("OFF cannot add an archive row", saved,
                SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE, null, 200).size());
        assertNotNull("OFF preserves existing saved records",
                NotificationTimelineStore.findExact(app, next.notificationId, next.postTime));
    }

    @Test public void catchUpAdvancesAcrossEqualTimestampPagesAndLateOldPosts() throws Exception {
        long start = NotificationTimelineStore.latestRowId(app);
        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            assertTrue(SmartStateStore.saveNotification(app, "notification://equal-time188/" + i,
                    "test.catchup188", "Catch up188", "Equal timestamp " + i, "Distinct message " + i,
                    timestamp, false, "", -1L, "", "", ""));
        }
        NotificationTimelineState.setLastPersistedRow(app, start);
        catchUp();
        PowerTestSupport187.await("First bounded catch-up page indexed", () ->
                NotificationTimelineState.getLastPersistedRow(app) >= start + 256);
        catchUp();
        long last = NotificationTimelineStore.latestRowId(app);
        PowerTestSupport187.await("Second page with identical post times indexed", () ->
                NotificationTimelineState.getLastPersistedRow(app) >= last);
        for (int i = 0; i < 300; i++) assertEquals(1,
                HistoryTestSupport188.historyCount(app, "notification://equal-time188/" + i));
        assertTrue(SmartStateStore.saveNotification(app, "notification://late188",
                "test.catchup188", "Catch up188", "Late arrival", "Older timestamp, later commit",
                timestamp - 60000L, false, "", -1L, "", "", ""));
        catchUp();
        PowerTestSupport187.await("Late commit is not lost behind a time watermark", () ->
                HistoryTestSupport188.historyCount(app, "notification://late188") == 1);
        catchUp();
        Thread.sleep(200L);
        assertEquals("Repeated recovery does not duplicate events", 1,
                HistoryTestSupport188.historyCount(app, "notification://late188"));
    }

    private NotificationHistoryRecord post(String mode) throws Exception {
        long before = NotificationTimelineStore.latestRowId(app);
        PowerTestSupport187.control(mode);
        PowerTestSupport187.await("Received event was durably committed", () -> latestReceived() != null
                && latestReceived().dbId > before);
        return latestReceived();
    }

    private NotificationHistoryRecord latestReceived() {
        List<NotificationHistoryRecord> rows = SmartStateStore.queryNotifications(app,
                PowerTestSupport187.FIXTURE, java.util.Collections.singletonList("Retained message188"), 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void refreshHome() {
        scenario.onActivity(activity -> SearchHandler.getInstance().search(
                Searcher.Type.HISTORY, activity, null, true));
    }

    private void awaitRow(String id, boolean present) throws Exception {
        HistoryTestSupport188.awaitUi(scenario, "Saved notification Home visibility=" + present,
                activity -> HistoryTestSupport188.hasRow(activity, id) == present);
    }

    private void reconnect() throws Exception {
        String component = app.getPackageName() + "/fr.neamar.kiss.notification.NotificationListener";
        PowerTestSupport187.shell("cmd notification disallow_listener " + component);
        PowerTestSupport187.await("Listener really disconnected", () ->
                !NotificationListener.isReadyForExactNotificationLookup());
        PowerTestSupport187.shell("cmd notification allow_listener " + component);
        PowerTestSupport187.await("Listener really reconnected", NotificationListener::isReadyForExactNotificationLookup);
    }

    private void catchUp() {
        scenario.onActivity(activity -> {
            Object manager = HistoryTestSupport188.field(activity, "forwarderManager");
            Object forwarder = HistoryTestSupport188.field(manager, "notificationForwarder");
            try {
                Method method = forwarder.getClass().getDeclaredMethod("catchUpPersistedTimelineAsync");
                method.setAccessible(true);
                method.invoke(forwarder);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }
}
