package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Process;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.utils.LauncherCrashJournal;

/** CI force-stops the app between these two methods. */
public final class HomeColdRestart192RegressionTest {
    @Test public void saveProcessBeforeManualRestart() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            HistoryTestSupport188.awaitUi(scenario, "Home ready before restart", a ->
                    KissApplication.getApplication(a).getDataHandler().isAllProvidersLoaded());
            assertTrue(app.getSharedPreferences("home-restart-192", 0).edit()
                    .putInt("previous-pid", Process.myPid()).commit());
        }
    }

    @Test public void homeAfterManualRestartDoesNotRestartTheProcessAgain() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int previous = app.getSharedPreferences("home-restart-192", 0).getInt("previous-pid", 0);
        assertTrue("Previous process was recorded", previous > 0);
        assertNotEquals("Force-stop really created a new process", previous, Process.myPid());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            HistoryTestSupport188.awaitUi(scenario, "Home ready after restart", a ->
                    KissApplication.getApplication(a).getDataHandler().isAllProvidersLoaded());
            AtomicReference<MainActivity> activity = new AtomicReference<>();
            AtomicReference<RecordAdapter> adapter = new AtomicReference<>();
            scenario.onActivity(a -> { activity.set(a); adapter.set(a.adapter); });
            int process = Process.myPid();
            PowerTestSupport187.shell("input keyevent KEYCODE_HOME");
            Thread.sleep(500L);
            scenario.onActivity(a -> {
                assertSame("HOME retains restarted activity", activity.get(), a);
                assertSame("HOME retains restarted adapter", adapter.get(), a.adapter);
            });
            assertEquals("HOME does not restart launcher process", process, Process.myPid());
            assertTrue(LauncherCrashJournal.buildShareReport(app).contains("Recent Android process exits"));
        }
    }
}
