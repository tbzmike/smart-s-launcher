package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.LauncherEntryActivity;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.utils.LauncherCrashJournal;

/** CI force-stops the app between these two methods. */
public final class HomeColdRestart192RegressionTest {
    @Test public void saveProcessBeforeManualRestart() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home)) {
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
        Intent open = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        assertNotNull("Android Settings can open the launcher", open);
        assertEquals("App Open routes through the HOME entry", LauncherEntryActivity.class.getName(),
                open.getComponent().getClassName());
        PowerTestSupport187.shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "
                + app.getPackageName() + "/fr.neamar.kiss.LauncherEntryActivity");
        PowerTestSupport187.await("App Open enters a resumed HOME activity", () -> resumedHome() != null);
        MainActivity first = resumedHome();
        assertTrue("Entry opened the actual HOME task", first.getIntent().hasCategory(Intent.CATEGORY_HOME));
        PowerTestSupport187.await("Home providers ready after restart", () ->
                KissApplication.getApplication(app).getDataHandler().isAllProvidersLoaded());
        RecordAdapter adapter = first.adapter;
        int process = Process.myPid();
        PowerTestSupport187.shell("input keyevent KEYCODE_HOME");
        PowerTestSupport187.await("HOME returns to the same activity", () -> resumedHome() == first);
        assertSame("HOME retains restarted adapter", adapter, resumedHome().adapter);
        assertEquals("HOME does not restart launcher process", process, Process.myPid());
        assertTrue(LauncherCrashJournal.buildShareReport(app).contains("Recent Android process exits"));
    }

    private static MainActivity resumedHome() {
        AtomicReference<MainActivity> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (activity instanceof MainActivity) result.set((MainActivity) activity);
            }
        });
        return result.get();
    }
}
