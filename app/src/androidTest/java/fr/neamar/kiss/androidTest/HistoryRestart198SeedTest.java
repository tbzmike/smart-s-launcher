package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.util.List;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ValuedHistoryRecord;
import fr.neamar.kiss.pojo.AppPojo;

/**
 * First half of a TRUE process-restart test. The CI workflow force-stops the target
 * application after this test; this is not an Activity.recreate() imitation.
 */
public final class HistoryRestart198SeedTest {
    public static final String KEY = "test-history-restart-198-item-id";
    @Test public void seedHistoryDatabaseWithARealInstalledApp() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        assertTrue(prefs.edit().putString("number-of-history-results", "50003")
                .putString("smart-history-layout", "vertical")
                .putBoolean("history-hide", false)
                .putBoolean("exclude-favorites-history", false)
                .putBoolean("freeze-history", false).commit());
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            DataHandler handler = KissApplication.getApplication(app).getDataHandler();
            for (int i = 0; i < 80 && !handler.isAllProvidersLoaded(); i++) Thread.sleep(250);
            assertTrue("App providers must load before selecting a real History target",
                    handler.isAllProvidersLoaded());
            List<AppPojo> apps = handler.getApplications();
            assertFalse("Emulator must have at least one installed app", apps.isEmpty());
            String id = null;
            for (AppPojo candidate : apps) {
                if (candidate != null && candidate.getHistoryId() != null
                        && !candidate.isExcludedFromHistory()) {
                    id = candidate.getHistoryId();
                    break;
                }
            }
            assertTrue("At least one launchable app is required", id != null && !id.isEmpty());
            DBHelper.clearHistory(app);
            DBHelper.insertHistory(app, null, id);
            assertTrue(prefs.edit().putString(KEY, id).commit());
            List<ValuedHistoryRecord> stored = DBHelper.getRecentHistoryCandidates(app, 40);
            assertFalse("History candidate query must find newly saved app", stored.isEmpty());
            assertTrue("Database must contain the exact saved app History ID",
                    id.equals(stored.get(0).record));
        }
    }
}
