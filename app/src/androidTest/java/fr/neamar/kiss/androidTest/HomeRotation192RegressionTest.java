package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.Intent;
import android.database.DataSetObserver;
import android.os.Process;
import android.view.View;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.utils.LauncherCrashJournal;

/** Real HOME delivery and Android configuration changes, with the launcher as default HOME. */
public final class HomeRotation192RegressionTest {
    @Test public void homeAndRotationKeepTheActivityAndExistingHistoryInAllLayouts() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        configure(prefs);
        for (String mode : new String[]{"vertical", "vertical_cards", "wheel_3d"}) {
            prefs.edit().putString("smart-history-layout", mode).commit();
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                AtomicReference<String> id = new AtomicReference<>();
                HistoryTestSupport188.awaitUi(scenario, "Fixture app and providers loaded", a -> {
                    if (!KissApplication.getApplication(a).getDataHandler().isAllProvidersLoaded()) return false;
                    for (AppPojo pojo : KissApplication.getApplication(a).getDataHandler().getApplications()) {
                        if (PowerTestSupport187.FIXTURE.equals(pojo.packageName)) {
                            id.set(pojo.getHistoryId());
                            return true;
                        }
                    }
                    return false;
                });
                DBHelper.insertHistory(app, null, id.get());
                scenario.onActivity(MainActivity::showHistory);
                HistoryTestSupport188.awaitUi(scenario, "History fixture is visible: " + mode,
                        a -> HistoryTestSupport188.hasRow(a, id.get()));
                HistoryTestSupport188.awaitUi(scenario, "Initial History search settled", a ->
                        HistoryTestSupport188.field(SearchHandler.getInstance(), "runningSearch") == null);
                Thread.sleep(300L);
                AtomicReference<MainActivity> activityRef = new AtomicReference<>();
                AtomicReference<RecordAdapter> adapterRef = new AtomicReference<>();
                AtomicReference<Result<?>> resultRef = new AtomicReference<>();
                int originalPid = Process.myPid();
                scenario.onActivity(a -> {
                    activityRef.set(a); adapterRef.set(a.adapter);
                    resultRef.set(find(a, id.get()));
                    assertNotNull(resultRef.get());
                });
                AtomicLong generation = (AtomicLong) HistoryTestSupport188.field(
                        SearchHandler.getInstance(), "searchGeneration");
                AtomicInteger adapterChanges = new AtomicInteger();
                DataSetObserver observer = new DataSetObserver() {
                    @Override public void onChanged() { adapterChanges.incrementAndGet(); }
                    @Override public void onInvalidated() { adapterChanges.incrementAndGet(); }
                };
                scenario.onActivity(a -> a.adapter.registerDataSetObserver(observer));
                PowerTestSupport187.shell("input keyevent KEYCODE_HOME");
                Thread.sleep(500L);
                scenario.onActivity(a -> {
                    assertSame("HOME retains the activity", activityRef.get(), a);
                    assertSame("HOME retains the History adapter", adapterRef.get(), a.adapter);
                    assertSame("HOME retains the existing History result and icon", resultRef.get(), find(a, id.get()));
                    assertEquals("Unchanged HOME does not republish visible History", 0, adapterChanges.get());
                    a.adapter.unregisterDataSetObserver(observer);
                });
                assertEquals("HOME does not restart the process", originalPid, Process.myPid());

                // Android may stop HOME before delivering the real key event, so exercise the
                // distinct paused-but-not-stopped foreground redelivery as well.
                scenario.moveToState(Lifecycle.State.STARTED);
                long beforeForegroundDelivery = generation.get();
                scenario.onActivity(a -> {
                    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(a, home);
                });
                scenario.moveToState(Lifecycle.State.RESUMED);
                Thread.sleep(250L);
                assertEquals("Foreground HOME does not restart History search",
                        beforeForegroundDelivery, generation.get());
                rotate(scenario, activityRef.get(), adapterRef.get(), resultRef.get(), id.get(),
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE);
                rotate(scenario, activityRef.get(), adapterRef.get(), resultRef.get(), id.get(),
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT);
                scenario.onActivity(a -> a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER));
            }
        }
    }

    @Test public void activeSearchAndItsButtonsSurviveRealRotation() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        configure(prefs);
        prefs.edit().putString("search-keyboard-mode", "built-in")
                .putInt("built-in-keyboard-width-percent", 100)
                .putInt("built-in-keyboard-height-percent", 30).commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> instance = new AtomicReference<>();
            HistoryTestSupport188.awaitUi(scenario, "Providers ready", a ->
                    KissApplication.getApplication(a).getDataHandler().isAllProvidersLoaded());
            scenario.onActivity(a -> {
                instance.set(a);
                a.searchEditText.setText("fixture192");
                a.searchEditText.showBuiltInKeyboard();
                assertEquals(View.VISIBLE, a.findViewById(R.id.searchQuickActions).getVisibility());
                assertTrue("Built-in keyboard is visible", a.searchEditText.isBuiltInKeyboardVisible());
                a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            });
            HistoryTestSupport188.awaitUi(scenario, "Actual landscape configuration received", a ->
                    a.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
            HistoryTestSupport188.awaitUi(scenario, "Keyboard resizes after actual landscape layout", a -> {
                View keyboard = (View) HistoryTestSupport188.field(a.searchEditText, "builtInKeyboard");
                View root = (View) HistoryTestSupport188.field(a.searchEditText, "keyboardRoot");
                return keyboard != null && root != null && root.getWidth() > 0
                        && keyboard.getLayoutParams().width == root.getWidth()
                        && keyboard.getLayoutParams().height <= root.getHeight() / 2;
            });
            scenario.onActivity(a -> {
                assertSame("Search activity is retained", instance.get(), a);
                assertTrue("Keyboard remains visible after rotation", a.searchEditText.isBuiltInKeyboardVisible());
                assertEquals("Search text survives rotation", "fixture192", a.searchEditText.getText().toString());
                assertEquals(View.VISIBLE, a.findViewById(R.id.searchQuickActions).getVisibility());
                assertTrue("Search database button remains wired", a.findViewById(R.id.searchDatabaseNow).performClick());
                a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
            });
        }
    }

    @Test public void oldSavedCrashIsClearlyDatedAndAndroidExitsAreIncluded() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = new File(app.getFilesDir(), "smart-s-last-java-crash.txt");
        byte[] original = file.isFile() ? Files.readAllBytes(file.toPath()) : null;
        String old = "Smart S Launcher Java crash at Mon Nov 02 12:51:52 GMT+02:00 2026\nThread: old-test\n";
        try {
            Files.write(file.toPath(), old.getBytes(StandardCharsets.UTF_8));
            String report = LauncherCrashJournal.buildShareReport(app);
            assertTrue(report.contains("Current app version: 3.30.192"));
            assertTrue(report.contains("Recent Android process exits"));
            assertTrue(report.contains("may be from an earlier date or app version"));
            assertTrue(report.contains("Mon Nov 02 12:51:52 GMT+02:00 2026"));
            assertEquals("Sharing never overwrites the saved evidence", old, LauncherCrashJournal.readLatest(app));
        } finally {
            if (original == null) Files.deleteIfExists(file.toPath());
            else Files.write(file.toPath(), original);
        }
    }

    private static void configure(SharedPreferences prefs) {
        prefs.edit().putBoolean("enable-notification-history", false)
                .putBoolean("enable-app-usage-tracking", false)
                .putBoolean("smart-battery-monitor-enabled", false)
                .putBoolean("app-usage-default-repaired-v33001", true)
                .putBoolean("semantic-search-enabled", false)
                .putBoolean("smart-index-auto-refresh", false)
                .putBoolean("history-hide", false).putBoolean("history-onkeyboard", false)
                .putBoolean("exclude-favorites-history", false)
                .putString("number-of-history-results", "803")
                .putString("number-of-search-results", "155").commit();
    }

    private static Result<?> find(MainActivity activity, String id) {
        for (int i = 0; i < activity.adapter.getCount(); i++) {
            Result<?> result = activity.adapter.getItem(i);
            if (id.equals(result.getPojoId())) return result;
        }
        return null;
    }

    private static void rotate(ActivityScenario<MainActivity> scenario, MainActivity original,
                               RecordAdapter adapter, Result<?> result, String id,
                               int requested, int expected) throws Exception {
        scenario.onActivity(a -> a.setRequestedOrientation(requested));
        HistoryTestSupport188.awaitUi(scenario, "Real rotation applied", a ->
                a.getResources().getConfiguration().orientation == expected);
        scenario.onActivity(a -> {
            assertSame("Rotation does not recreate Home", original, a);
            assertSame("Rotation retains the History adapter", adapter, a.adapter);
            assertSame("Rotation retains the History result and icon", result, find(a, id));
            assertNotNull("Search field still exists", a.searchEditText);
            assertTrue("Search bar remains visible", a.findViewById(R.id.searchEditLayout).getVisibility() == View.VISIBLE);
        });
    }
}
