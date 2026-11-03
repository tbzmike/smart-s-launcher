package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.battery.BatteryMonitorService;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.notification.NotificationListenerRecoveryReceiver;

/** Exercises actual Android service binding, screen state and notification delivery. */
public final class NotificationShutdown190RegressionTest {
    @Test public void offStopsListenerAcrossScreenOffAndOnRestoresCapture() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        Map<String, ?> original = prefs.getAll();
        String[] keys = {"enable-notification-history", "enable-app-usage-tracking",
                "smart-battery-monitor-enabled", "app-usage-default-repaired-v33001"};
        PowerManager power = app.getSystemService(PowerManager.class);
        ActivityScenario<MainActivity> scenario = null;
        try {
            prefs.edit().putBoolean(keys[0], true).putBoolean(keys[1], false)
                    .putBoolean(keys[2], false).putBoolean(keys[3], true).commit();
            PowerTestSupport187.connect(app);
            PowerTestSupport187.control("notify-clear");
            scenario = ActivityScenario.launch(MainActivity.class);
            PowerTestSupport187.control("notify-one");
            PowerTestSupport187.await("Initial received notification is saved", () ->
                    !SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE, null, 100).isEmpty());
            int saved = SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE, null, 100).size();

            prefs.edit().putBoolean(keys[0], false).commit();
            ComponentName component = new ComponentName(app, NotificationListener.class);
            PowerTestSupport187.await("Android listener is actually stopped and disabled", () ->
                    !serviceRunning(app, NotificationListener.class)
                            && app.getPackageManager().getComponentEnabledSetting(component)
                            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            assertFalse(NotificationListener.requestListenerReconnect(app));
            new NotificationListenerRecoveryReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
            new NotificationListenerRecoveryReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));

            PowerTestSupport187.shell("input keyevent KEYCODE_SLEEP");
            PowerTestSupport187.await("Screen really turned off", () -> !power.isInteractive());
            long beforeCpu = android.os.Process.getElapsedCpuTime();
            PowerTestSupport187.control("notify-burst");
            String deferredPackage = "test.screenoff190";
            fr.neamar.kiss.searcher.AppMetadataSyncScheduler.enqueuePackage(app, deferredPackage,
                    "Screen-off deferral regression");
            assertTrue("Package update remains queued", fr.neamar.kiss.searcher.AppMetadataSyncScheduler
                    .pendingPackages(app).contains(deferredPackage));
            assertTrue("No automatic metadata service is scheduled while screen is off", app
                    .getSystemService(JobScheduler.class).getAllPendingJobs().stream().noneMatch(job ->
                            job.getService().getClassName().endsWith("AppMetadataSyncJobService")));
            Thread.sleep(1200L);
            android.util.Log.i("SmartS190Power", "disabled-screen-off CPU ms="
                    + (android.os.Process.getElapsedCpuTime() - beforeCpu));
            assertFalse("Screen-off notifications must not restart listener", serviceRunning(app, NotificationListener.class));
            assertNull(NotificationListener.activeNotificationsSnapshot());
            assertFalse("Disabled battery service remains stopped", serviceRunning(app, BatteryMonitorService.class));
            JobScheduler jobs = app.getSystemService(JobScheduler.class);
            assertTrue("Disabled usage tracking schedules no usage job", jobs.getAllPendingJobs().stream()
                    .noneMatch(job -> job.getService().getClassName().endsWith("AppUsageJobService")));
            assertEquals("Previously saved history is retained", saved,
                    SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE, null, 100).size());

            PowerTestSupport187.control("notify-clear");
            prefs.edit().putBoolean(keys[0], true).commit();
            PowerTestSupport187.await("Listener reconnects with the existing access grant",
                    NotificationListener::isReadyForExactNotificationLookup);
            assertTrue("Notification access remains granted", NotificationManagerCompat
                    .getEnabledListenerPackages(app).contains(app.getPackageName()));
            assertFalse("Capture is checked while screen remains off", power.isInteractive());
            PowerTestSupport187.control("notify-history-one");
            PowerTestSupport187.await("Screen-off notification is saved when enabled", () ->
                    !SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE,
                            Collections.singletonList("needle188"), 100).isEmpty());
            PowerTestSupport187.shell("input keyevent KEYCODE_WAKEUP");
            PowerTestSupport187.shell("wm dismiss-keyguard");
            PowerTestSupport187.await("Screen wakes", power::isInteractive);
            fr.neamar.kiss.searcher.AppMetadataSyncScheduler.resumePending(app);
            assertNotNull("Queued package update is scheduled again on wake",
                    app.getSystemService(JobScheduler.class).getPendingJob(0x53534D44));
            fr.neamar.kiss.searcher.AppMetadataSyncScheduler.removePackage(app, deferredPackage);
            app.getSystemService(JobScheduler.class).cancel(0x53534D44);
        } finally {
            PowerTestSupport187.shell("input keyevent KEYCODE_WAKEUP");
            PowerTestSupport187.shell("wm dismiss-keyguard");
            PowerTestSupport187.control("notify-clear");
            if (scenario != null) scenario.close();
            SharedPreferences.Editor restore = prefs.edit();
            for (String key : keys) {
                if (original.get(key) instanceof Boolean) restore.putBoolean(key, (Boolean) original.get(key));
                else restore.remove(key);
            }
            restore.commit();
        }
    }

    private static boolean serviceRunning(Context app, Class<?> service) {
        ActivityManager manager = app.getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (service.getName().equals(info.service.getClassName())) return true;
        }
        return false;
    }
}
