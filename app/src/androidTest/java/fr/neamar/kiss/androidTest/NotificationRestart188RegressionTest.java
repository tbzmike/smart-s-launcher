package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import org.junit.Test;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.NotificationTimelineStore;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.searcher.SearchHandler;

/** CI force-stops the launcher process between these two independently selected methods. */
public final class NotificationRestart188RegressionTest {
    @Test public void seedReceivedAndDismissedNotification() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HistoryTestSupport188.configure(app);
        PowerTestSupport187.connect(app);
        PowerTestSupport187.control("notify-clear");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            PowerTestSupport187.control("notify-history-one");
            PowerTestSupport187.await("Receipt archived", () -> !rows(app).isEmpty());
            NotificationHistoryRecord record = rows(app).get(0);
            assertTrue(HistoryTestSupport188.historyCount(app, record.notificationId) > 0);
            app.getSharedPreferences("restart188", 0).edit()
                    .putString("id", record.notificationId).putLong("post", record.postTime).commit();
            PowerTestSupport187.control("notify-clear");
            scenario.onActivity(activity -> SearchHandler.getInstance().cancelSearch());
        }
    }

    @Test public void savedNotificationSurvivesARealProcessRestart() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id = app.getSharedPreferences("restart188", 0).getString("id", null);
        long time = app.getSharedPreferences("restart188", 0).getLong("post", 0L);
        assertNotNull("Previous process saved a real receipt", id);
        assertNotNull(NotificationTimelineStore.findExact(app, id, time));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            HistoryTestSupport188.awaitUi(scenario, "Cold Home resolved saved notification", activity ->
                    HistoryTestSupport188.hasRow(activity, id));
            HistoryTestSupport188.screenshot(app, "notification-after-process-restart188");
            scenario.onActivity(activity -> SearchHandler.getInstance().cancelSearch());
        }
    }

    private static List<NotificationHistoryRecord> rows(Context app) {
        return SmartStateStore.queryNotifications(app, PowerTestSupport187.FIXTURE,
                java.util.Collections.singletonList("Retained message188"), 1);
    }
}
