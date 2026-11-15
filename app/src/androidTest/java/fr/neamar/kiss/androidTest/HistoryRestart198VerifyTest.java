package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.result.Result;

/** Executes in a NEW process after a genuine adb force-stop between test stages. */
public final class HistoryRestart198VerifyTest {
    @Test public void previouslySavedHistoryActuallyRendersAfterProcessRestart() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        String id = prefs.getString(HistoryRestart198SeedTest.KEY, null);
        assertNotNull("The previous process must persist the test History item", id);
        assertTrue(DBHelper.getHistoryLength(app) > 0);
        final boolean[] appeared = {false};
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            // Cold launch + asynchronous provider load + bounded preview / full History.
            // The UI must not need a second HOME press or manual force-stop.
            for (int i = 0; i < 96 && !appeared[0]; i++) {
                scenario.onActivity(activity -> {
                    if (activity.adapter == null || activity.adapter.isEmpty()) return;
                    for (int j = 0; j < activity.adapter.getCount(); j++) {
                        Result<?> result = activity.adapter.getItem(j);
                        if (result != null && result.getPojo() != null
                                && id.equals(result.getPojo().getHistoryId())) {
                            appeared[0] = true;
                            return;
                        }
                    }
                });
                if (!appeared[0]) Thread.sleep(250L);
            }
        } finally {
            DBHelper.clearHistory(app);
            prefs.edit().remove(HistoryRestart198SeedTest.KEY).apply();
        }
        assertTrue("Cold process restart must visibly restore the same persisted History item",
                appeared[0]);
    }
}
