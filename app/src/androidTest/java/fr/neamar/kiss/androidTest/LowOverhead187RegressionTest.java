package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;
import android.app.ActivityManager;
import android.app.Notification;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.util.LruCache;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.appusage.AppUsageSync;
import fr.neamar.kiss.appusage.AppUsageTracker;
import fr.neamar.kiss.battery.BatteryMonitorService;
import fr.neamar.kiss.battery.BatteryMonitorStarter;
import fr.neamar.kiss.db.AppUsageTodayStore;
import fr.neamar.kiss.db.HistoryItemUsageTodayStore;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.notification.NotificationAvatarSupport;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.notification.NotificationVisualSupport;
import fr.neamar.kiss.utils.IdleExecutors;
import fr.neamar.kiss.utils.LauncherScrollWorkGate;

public final class LowOverhead187RegressionTest {
    private Context app;
    private SharedPreferences prefs;
    private Map<String, ?> original;
    private ActivityScenario<MainActivity> scenario;
    private static final String[] KEYS = {"enable-app-usage-tracking", "enable-notification-history",
            "smart-battery-monitor-enabled", "app-usage-default-repaired-v33001"};

    @Before public void setUp() throws Exception {
        app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        original = prefs.getAll();
        prefs.edit().putBoolean(KEYS[0], false).putBoolean(KEYS[1], false)
                .putBoolean(KEYS[2], false).putBoolean(KEYS[3], true).commit();
        LauncherScrollWorkGate.setScrolling(false);
        PowerTestSupport187.shell("appops set " + app.getPackageName() + " GET_USAGE_STATS allow");
        PowerTestSupport187.control("notify-clear");
        scenario = ActivityScenario.launch(MainActivity.class);
    }
    @After public void tearDown() throws Exception {
        LauncherScrollWorkGate.setScrolling(false);
        PowerTestSupport187.control("notify-clear");
        BatteryMonitorStarter.setEnabled(app, false);
        if (scenario != null) scenario.close();
        SharedPreferences.Editor restore = prefs.edit();
        for (String k : KEYS) {
            if (original.get(k) instanceof Boolean) restore.putBoolean(k, (Boolean) original.get(k));
            else restore.remove(k);
        }
        restore.commit();
    }

    private static final class NoUsageQueries extends ContextWrapper {
        int attempts;
        NoUsageQueries(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public Object getSystemService(String name) {
            if (USAGE_STATS_SERVICE.equals(name) || APP_OPS_SERVICE.equals(name)) {
                attempts++;
                throw new AssertionError("Disabled usage must not query " + name);
            }
            return super.getSystemService(name);
        }
        @Override public android.content.pm.PackageManager getPackageManager() {
            throw new AssertionError("Disabled sync must not scan installed packages");
        }
    }

    @Test public void explicitOffSurvivesMigrationAndBlocksUsageQueriesEvenWithPermission() {
        prefs.edit().remove(KEYS[3]).commit();
        AppUsageTracker.repairPerformanceRegression(app);
        assertFalse(AppUsageTracker.isEnabled(app));
        NoUsageQueries forbidden = new NoUsageQueries(app);
        assertFalse(AppUsageTodayStore.getToday(forbidden).available);
        assertFalse(HistoryItemUsageTodayStore.getToday(forbidden,
                Collections.singletonMap("shortcut://187", "test.publisher"), true).available);
        AppUsageSync.sync(forbidden);
        AppUsageTracker.syncNow(forbidden);
        assertEquals(0, forbidden.attempts);
        android.app.job.JobScheduler scheduler = app.getSystemService(android.app.job.JobScheduler.class);
        assertTrue(scheduler.getAllPendingJobs().stream().noneMatch(
                job -> job.getService().getClassName().endsWith("AppUsageJobService")));
    }

    @Test public void disablingUsageRejectsACachedEnabledSnapshot() {
        prefs.edit().putBoolean(KEYS[0], true).commit();
        AppUsageTodayStore.invalidate();
        assertTrue("Real usage access remains supported", AppUsageTodayStore.getToday(app).available);
        prefs.edit().putBoolean(KEYS[0], false).commit();
        NoUsageQueries forbidden = new NoUsageQueries(app);
        assertFalse(AppUsageTodayStore.getToday(forbidden).available);
        assertEquals(0, forbidden.attempts);
    }

    @Test public void batteryOffStopsTheServiceAndRejectsAStaleStart() throws Exception {
        scenario.onActivity(activity -> BatteryMonitorStarter.setEnabled(activity, true));
        PowerTestSupport187.await("Enabled monitor enters foreground", () -> batteryForeground());
        scenario.onActivity(activity -> BatteryMonitorStarter.setEnabled(activity, false));
        PowerTestSupport187.await("OFF stops the actual service", () -> !batteryRunning());
        scenario.onActivity(activity -> activity.startService(new Intent(activity, BatteryMonitorService.class)
                .setAction(BatteryMonitorService.ACTION_START)));
        Thread.sleep(300L);
        assertFalse(BatteryMonitorStarter.isEnabled(app));
        PowerTestSupport187.await("Stale START cannot resurrect monitor", () -> !batteryRunning());
        PowerTestSupport187.await("Battery worker stack is released", () ->
                Thread.getAllStackTraces().keySet().stream().noneMatch(
                        t -> t.isAlive() && "smart-s-battery-monitor".equals(t.getName())));
        scenario.onActivity(activity -> {
            BatteryMonitorStarter.setEnabled(activity, true);
            BatteryMonitorStarter.setEnabled(activity, false);
        });
        Thread.sleep(300L);
        PowerTestSupport187.await("Immediate ON/OFF completes the Android handshake and stops",
                () -> !batteryRunning());
        assertFalse(BatteryMonitorStarter.isEnabled(app));
    }
    private boolean batteryRunning() {
        ActivityManager manager = app.getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (BatteryMonitorService.class.getName().equals(info.service.getClassName())) return true;
        }
        return false;
    }

    private boolean batteryForeground() {
        ActivityManager manager = app.getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (BatteryMonitorService.class.getName().equals(info.service.getClassName())
                    && info.foreground) return true;
        }
        return false;
    }

    @Test public void disabledHistoryStopsListenerAndDoesNotArchive() throws Exception {
        prefs.edit().putBoolean(KEYS[1], true).commit();
        PowerTestSupport187.connect(app);
        PowerTestSupport187.control("notify-one");
        PowerTestSupport187.await("Real notification is verified", () -> fixtureNotification() != null);
        PowerTestSupport187.await("First notification is saved", () ->
                !SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE, null, 100).isEmpty());
        int beforeRows = SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE, null, 100).size();
        prefs.edit().putBoolean(KEYS[1], false).commit();
        PowerTestSupport187.await("Disabled history stops Android listener", () -> !listenerRunning()
                && app.getPackageManager().getComponentEnabledSetting(new android.content.ComponentName(
                app, NotificationListener.class)) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        assertNull("No live snapshot is served while disabled", NotificationListener.activeNotificationsSnapshot());
        assertFalse("A reconnect request cannot restart the listener", NotificationListener.requestListenerReconnect(app));
        new fr.neamar.kiss.notification.NotificationListenerRecoveryReceiver().onReceive(app,
                new Intent(Intent.ACTION_BOOT_COMPLETED));
        PowerTestSupport187.control("notify-burst");
        Thread.sleep(700L);
        assertFalse("A notification burst does not restart listener", listenerRunning());
        assertEquals(beforeRows, SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE, null, 100).size());
    }

    @Test public void reenabledHistoryStillSavesPicturesAndOffPreservesExistingRecords() throws Exception {
        prefs.edit().putBoolean(KEYS[1], true).commit();
        PowerTestSupport187.connect(app);
        PowerTestSupport187.control("notify-one");
        PowerTestSupport187.await("Exact notification is available", () -> fixtureNotification() != null);
        String id = NotificationListener.getTimelineId(fixtureNotification());
        PowerTestSupport187.await("Archive captures when enabled", () -> {
            String path = app.getSharedPreferences("notification-visual-history", 0).getString(id + "|image", "");
            return !path.isEmpty() && new File(path).isFile()
                    && !SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE, null, 100).isEmpty();
        });
        int saved = SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE, null, 100).size();
        prefs.edit().putBoolean(KEYS[1], false).commit();
        PowerTestSupport187.await("Listener stops", () -> !listenerRunning());
        PowerTestSupport187.control("notify-fresh");
        Thread.sleep(500L);
        assertEquals("Turning OFF keeps saved history and cannot add more", saved,
                SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE, null, 100).size());
        prefs.edit().putBoolean(KEYS[1], true).commit();
        PowerTestSupport187.await("Re-enabled history reconnects without granting access again",
                NotificationListener::isReadyForExactNotificationLookup);
        PowerTestSupport187.control("notify-history-one");
        PowerTestSupport187.await("New notifications are saved after re-enabling", () ->
                SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE, Collections.singletonList("needle188"), 100).size() > 0);
    }

    private boolean listenerRunning() {
        ActivityManager manager = app.getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (NotificationListener.class.getName().equals(info.service.getClassName())) return true;
        }
        return false;
    }

    @Test public void idleWorkersReleaseStacksAndRestartAndAvatarBudgetShrinks() throws Exception {
        Field f = NotificationAvatarSupport.class.getDeclaredField("MEMORY_CACHE");
        f.setAccessible(true);
        LruCache<?, ?> cache = (LruCache<?, ?>) f.get(null);
        assertEquals(2 * 1024 * 1024, cache.maxSize());
        scenario.onActivity(a -> prefs.edit().putBoolean(KEYS[1], true).commit());
        assertEquals(8 * 1024 * 1024, cache.maxSize());
        scenario.onActivity(a -> prefs.edit().putBoolean(KEYS[1], false).commit());
        assertEquals(2 * 1024 * 1024, cache.maxSize());
        ThreadPoolExecutor worker = IdleExecutors.single("smart-s-187-idle-proof");
        try {
            assertTrue(worker.submit(() -> Process.getThreadPriority(Process.myTid()) >= 10).get(3, TimeUnit.SECONDS));
            Thread.sleep(11000L);
            assertEquals("No native worker stack retained when idle", 0, worker.getPoolSize());
            assertEquals(Integer.valueOf(187), worker.submit(() -> 187).get(3, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }

    private StatusBarNotification fixtureNotification() {
        StatusBarNotification[] active = NotificationListener.activeNotificationsSnapshot();
        if (active != null) for (StatusBarNotification n : active) {
            if (n != null && PowerTestSupport187.FIXTURE.equals(n.getPackageName()) && n.getId() == 8700) return n;
        }
        return null;
    }
}
