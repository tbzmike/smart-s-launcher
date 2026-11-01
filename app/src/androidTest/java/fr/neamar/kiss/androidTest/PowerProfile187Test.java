package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

/** Runs unchanged against 3.30.186 and 3.30.187, on the same emulator and debug build settings. */
public final class PowerProfile187Test {
    @Test public void measureDisabledFeaturesAndRealNotificationTraffic() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putBoolean("enable-notification-history", false)
                .putBoolean("enable-app-usage-tracking", false)
                .putBoolean("app-usage-default-repaired-v33001", true)
                .putBoolean("smart-battery-monitor-enabled", false)
                .putBoolean("semantic-search-enabled", false)
                .putString("number-of-history-results", "803")
                .putString("smart-history-layout", "vertical")
                .putString("smart-text-overflow-mode", "auto_expand").commit();
        PowerTestSupport187.connect(app);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            PowerTestSupport187.await("Providers loaded before profiling",
                    () -> KissApplication.getApplication(app).getDataHandler().isAllProvidersLoaded());
            scenario.onActivity(activity -> {
                SearchHandler.getInstance().search(Searcher.Type.HISTORY, activity, null, false);
                SearchHandler.getInstance().cancelSearch();
                List<Pojo> rows = new ArrayList<>();
                for (int i = 0; i < 803; i++) {
                    SettingPojo p = new SettingPojo("setting://power187/" + i, "", -1);
                    p.setName("Saved history row " + i);
                    rows.add(p);
                }
                activity.adapter.clear();
                activity.adapter.updateWithPojos(activity, rows, false, "<history>");
                assertEquals(803, activity.adapter.getCount());
            });
            PowerTestSupport187.control("notify-clear");
            Thread.sleep(1200L);
            long foregroundCpu = Process.getElapsedCpuTime();
            long foregroundStart = SystemClock.elapsedRealtime();
            Thread.sleep(12000L);
            long foregroundCpuMs = Process.getElapsedCpuTime() - foregroundCpu;
            long foregroundWallMs = SystemClock.elapsedRealtime() - foregroundStart;
            Debug.MemoryInfo foregroundMemory = new Debug.MemoryInfo();
            Debug.getMemoryInfo(foregroundMemory);
            // Keep the saved Home window warm while another activity owns the screen. Disabled
            // features must not process/archive notification pictures behind the user's apps.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);
            long workCpu = Process.getElapsedCpuTime();
            long workStart = SystemClock.elapsedRealtime();
            PowerTestSupport187.control("notify-load");
            PowerTestSupport187.control("notify-burst");
            Thread.sleep(12000L); // Includes completion and idle-worker timeout for both builds.
            long burstCpuMs = Process.getElapsedCpuTime() - workCpu;
            long burstWallMs = SystemClock.elapsedRealtime() - workStart;
            long idleCpu = Process.getElapsedCpuTime();
            long idleStart = SystemClock.elapsedRealtime();
            Thread.sleep(15000L);
            long idleCpuMs = Process.getElapsedCpuTime() - idleCpu;
            long idleWallMs = SystemClock.elapsedRealtime() - idleStart;
            System.gc();
            Thread.sleep(300L);
            Debug.MemoryInfo memory = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memory);
            int optionalThreads = 0;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.isAlive() && t.getName().startsWith("smart-s-")
                        && !t.getName().contains("main")) optionalThreads++;
            }
            JSONObject metrics = new JSONObject()
                    .put("profile", InstrumentationRegistry.getArguments().getString("profile", "current"))
                    .put("version", app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName)
                    .put("pssKb", memory.getTotalPss())
                    .put("foregroundPssKb", foregroundMemory.getTotalPss())
                    .put("foregroundIdleCpuMs", foregroundCpuMs)
                    .put("foregroundIdleWallMs", foregroundWallMs)
                    .put("foregroundIdleSingleCorePercent", foregroundCpuMs * 100.0 / foregroundWallMs)
                    .put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    .put("notificationBurstCpuMs", burstCpuMs).put("notificationBurstWallMs", burstWallMs)
                    .put("idleCpuMs", idleCpuMs).put("idleWallMs", idleWallMs)
                    .put("idleSingleCorePercent", idleCpuMs * 100.0 / idleWallMs)
                    .put("optionalWorkerThreadsAfterIdle", optionalThreads)
                    .put("notificationArchiveFiles", PowerTestSupport187.files(new java.io.File(app.getFilesDir(), "notification_visuals")))
                    .put("avatarArchiveFiles", PowerTestSupport187.files(new java.io.File(app.getFilesDir(), "notification_avatars")));
            System.out.println("POWER_PROFILE_187 " + metrics);
            android.util.Log.i("PowerProfile187", "POWER_PROFILE_187 " + metrics);
            assertTrue(memory.getTotalPss() > 0);
            PowerTestSupport187.control("notify-clear");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> SearchHandler.getInstance().cancelSearch());
        }
    }
}
