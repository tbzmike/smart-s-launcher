package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.DataActivityViewerActivity;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.IconsHandler;
import fr.neamar.kiss.R;
import fr.neamar.kiss.SettingsActivity;
import fr.neamar.kiss.SettingsFragment;
import fr.neamar.kiss.db.AppSourceMetadataRecord;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.AppMetadataSyncScheduler;
import fr.neamar.kiss.searcher.AppSourceMetadataUpdater;
import fr.neamar.kiss.searcher.SemanticHnswIndex;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.ui.ChronologicalDateScroller;
import fr.neamar.kiss.utils.RecentLaunchTracker;

public class FeaturePortBehaviorTest {
    private Context context;
    private SharedPreferences prefs;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        assertEquals(PackageManager.PERMISSION_GRANTED, context.getPackageManager()
                .checkPermission("android.permission.ACCESS_NETWORK_STATE", context.getPackageName()));
        prefs.edit().putBoolean("semantic-search-enabled", false)
                .putBoolean(AppSourceMetadataUpdater.PREF_USE_SOURCE_DESCRIPTIONS, false)
                .putString("smart-history-layout", "vertical").commit();
        ((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(0x53534D44);
    }

    @Test public void coldIconCacheCreationDoesNotCrashConcurrentIconWorkers() throws Exception {
        IconsHandler icons = KissApplication.getApplication(context).getIconsHandler();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            for (String name : new String[]{"getIconsCacheDir", "getCustomIconsDir"}) {
                Method create = IconsHandler.class.getDeclaredMethod(name);
                create.setAccessible(true);
                File directory = (File) create.invoke(icons);
                deleteCache(directory);
                for (int round = 0; round < 30; round++) {
                    CountDownLatch ready = new CountDownLatch(8);
                    CountDownLatch start = new CountDownLatch(1);
                    List<Future<File>> results = new ArrayList<>();
                    for (int worker = 0; worker < 8; worker++) {
                        results.add(workers.submit(() -> {
                            ready.countDown();
                            assertTrue(start.await(5, TimeUnit.SECONDS));
                            return (File) create.invoke(icons);
                        }));
                    }
                    try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
                    finally { start.countDown(); }
                    for (Future<File> result : results) assertTrue(result.get(5, TimeUnit.SECONDS).isDirectory());
                    if (round < 29) assertTrue(directory.delete());
                }
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private static void deleteCache(File directory) {
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) {
            if (child.isDirectory()) deleteCache(child);
            else assertTrue(child.delete());
        }
        assertTrue(directory.delete());
    }

    @Test public void dateControlHasOneOwnerAndDoesNotCoverHistoryRows() throws Exception {
        prefs.edit().putString("number-of-history-results", "50").commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            BaselineBehaviorTest.await(scenario, activity -> KissApplication.getApplication(activity)
                    .getDataHandler().isAllProvidersLoaded(), "providers for date control");
            BaselineBehaviorTest.awaitHistory(scenario);
            scenario.onActivity(activity -> {
                activity.searchEditText.setText("");
                activity.displayKissBar(false);
                SearchHandler.getInstance().cancelSearch();
                // Feed the real history loader. Adapter-only rows disappear legitimately when
                // a queued lifecycle/provider refresh reads the otherwise empty history DB.
                for (Result<?> result : BaselineBehaviorTest.fixtures(activity, true)) {
                    Pojo pojo = result.getPojo();
                    DBHelper.removeFromHistory(activity, pojo.getHistoryId());
                    RecentLaunchTracker.remember(pojo);
                    DBHelper.insertHistory(activity, "", pojo.getHistoryId());
                }
                SearchHandler.getInstance().search(Searcher.Type.HISTORY, activity, "", false);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            BaselineBehaviorTest.await(scenario, activity -> {
                View label = findDescription(activity.listContainer, "History date section");
                return label != null && label.getVisibility() == View.VISIBLE
                        && label.getHeight() > 0 && activity.adapter.getCount() == 24
                        && activity.list.getChildCount() > 0;
            }, "laid-out history date control");
            scenario.onActivity(activity -> activity.list.setSelection(0));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View label = findDescription(activity.listContainer, "History date section");
                View thumb = findDescription(activity.listContainer, "History date fast scroll");
                assertNotNull(label); assertNotNull(thumb);
                assertEquals(1, countDescription(activity.listContainer, "History date section"));
                assertEquals(1, countDescription(activity.listContainer, "History date fast scroll"));
                assertEquals(View.VISIBLE, label.getVisibility());
                assertTrue(activity.list.getTop() >= label.getBottom());
            });
            screenshot("history-date-control.png");
            scenario.onActivity(activity -> ((TextView) findDescription(activity.listContainer,
                    "History date section")).setTextSize(40));
            String[] layoutState = new String[1];
            try {
                BaselineBehaviorTest.await(scenario, activity -> {
                    TextView label = (TextView) findDescription(activity.listContainer, "History date section");
                    layoutState[0] = "activity=" + System.identityHashCode(activity)
                            + " textPx=" + label.getTextSize() + " labelHeight=" + label.getHeight()
                            + " labelBottom=" + label.getBottom() + " listTop=" + activity.list.getTop()
                            + " listMargin=" + ((FrameLayout.LayoutParams) activity.list.getLayoutParams()).topMargin
                            + " count=" + activity.adapter.getCount() + " visibility=" + label.getVisibility()
                            + " density=" + activity.getResources().getDisplayMetrics().density
                            + " query=" + activity.searchEditText.getText();
                    return label.getVisibility() == View.VISIBLE && activity.adapter.getCount() == 24
                            && label.getHeight() > 44 * activity.getResources().getDisplayMetrics().density
                            && activity.list.getTop() >= label.getBottom();
                }, "large date label reserves enough space");
            } catch (AssertionError e) {
                screenshot("history-large-date-control-failed.png");
                throw new AssertionError(layoutState[0], e);
            }
            screenshot("history-large-date-control.png");
            int[] originalFirst = new int[1];
            scenario.onActivity(activity -> {
                View thumb = findDescription(activity.listContainer, "History date fast scroll");
                float density = activity.getResources().getDisplayMetrics().density;
                float x = thumb.getWidth() - 2 * density;
                float center;
                try {
                    Field top = thumb.getClass().getDeclaredField("thumbTop");
                    top.setAccessible(true);
                    center = top.getFloat(thumb) + 29 * density;
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                originalFirst[0] = activity.list.getFirstVisiblePosition();
                float destination = center > thumb.getHeight() / 2f ? 1f : thumb.getHeight() - 1f;
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, center, 0);
                MotionEvent move = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_MOVE, x, destination, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 110, MotionEvent.ACTION_UP, x, destination, 0);
                try {
                    assertTrue(thumb.dispatchTouchEvent(down));
                    assertTrue(thumb.dispatchTouchEvent(move));
                    assertTrue(thumb.dispatchTouchEvent(up));
                } finally {
                    down.recycle(); move.recycle(); up.recycle();
                }
            });
            BaselineBehaviorTest.await(scenario, activity -> activity.list.getFirstVisiblePosition() != originalFirst[0],
                    "date scrubber moved through history");
            screenshot("history-date-scrubbed.png");
            for (String layout : new String[]{"vertical_cards", "wheel_3d"}) {
                prefs.edit().putString("smart-history-layout", layout).commit();
                scenario.recreate();
                BaselineBehaviorTest.awaitHistory(scenario);
                scenario.onActivity(activity -> {
                    View label = findDescription(activity.listContainer, "History date section");
                    View thumb = findDescription(activity.listContainer, "History date fast scroll");
                    assertNotNull(label); assertNotNull(thumb);
                    assertEquals(View.GONE, label.getVisibility());
                    assertEquals(View.GONE, thumb.getVisibility());
                    assertEquals(1, countDescription(activity.listContainer, "History date fast scroll"));
                });
            }
        } finally {
            for (int i = 0; i < 24; i++) {
                String id = "notification://verification-" + i;
                DBHelper.removeFromHistory(context, id);
                RecentLaunchTracker.clearIfMatches(id);
            }
        }
    }

    @Test public void chronologicalHeaderRestoresNonChronologicalLayoutAndDoesNotDuplicateViews() {
        // A window is needed for the follow-up layout requested when the header height changes.
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            FrameLayout[] host = new FrameLayout[1];
            ChronologicalDateScroller[] scroller = new ChronologicalDateScroller[1];
            scenario.onActivity(activity -> {
                host[0] = new FrameLayout(activity);
                ListView content = new ListView(activity);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
                params.topMargin = 7;
                host[0].addView(content, params);
                scroller[0] = new ChronologicalDateScroller(activity, host[0]);
                for (int i = 0; i < 20; i++) {
                    scroller[0].setSource(40, position -> System.currentTimeMillis(), (position, offset) -> {});
                }
                assertEquals(3, host[0].getChildCount());
                assertTrue(((FrameLayout.LayoutParams) content.getLayoutParams()).topMargin > 7);
                ((TextView) host[0].getChildAt(1)).setTextSize(40);
                activity.setContentView(host[0]);
            });
            AtomicBoolean laidOut = new AtomicBoolean();
            long deadline = SystemClock.uptimeMillis() + 30_000L;
            do {
                scenario.onActivity(activity -> {
                    View content = host[0].getChildAt(0), header = host[0].getChildAt(1);
                    laidOut.set(header.getHeight() > 0 && content.getTop() >= header.getBottom());
                });
                if (laidOut.get()) break;
                SystemClock.sleep(50);
            } while (SystemClock.uptimeMillis() < deadline);
            assertTrue("Large date label covers chronological rows", laidOut.get());
            scenario.onActivity(activity -> {
                scroller[0].setSource(0, null, null);
                assertEquals(7, ((FrameLayout.LayoutParams) host[0].getChildAt(0).getLayoutParams()).topMargin);
                assertEquals(View.GONE, host[0].getChildAt(1).getVisibility());
                assertEquals(View.GONE, host[0].getChildAt(2).getVisibility());
            });
        }
    }

    @Test public void queuedUpdateDeduplicatesBroadcastsAndPreservesNewerRequest() {
        String name = context.getPackageName();
        prefs.edit().remove("app-metadata-auto-update:" + name)
                .putStringSet("app-metadata-auto-pending-packages", Collections.emptySet()).commit();
        AppMetadataSyncScheduler.enqueuePackage(context, name, "verification install");
        Map<String, Long> first = AppMetadataSyncScheduler.pendingRequests(context);
        assertTrue(first.containsKey(name));
        AppMetadataSyncScheduler.enqueuePackage(context, name, "verification duplicate replacing broadcast");
        assertEquals(first, AppMetadataSyncScheduler.pendingRequests(context));

        // Emulate a newer observed revision arriving while the first batch is in flight.
        prefs.edit().putLong("app-metadata-auto-update:" + name, -1L).commit();
        AppMetadataSyncScheduler.enqueuePackage(context, name, "verification newer app revision");
        Map<String, Long> newer = AppMetadataSyncScheduler.pendingRequests(context);
        assertNotEquals(first.get(name), newer.get(name));
        AppMetadataSyncScheduler.acknowledge(context, first);
        assertTrue(AppMetadataSyncScheduler.pendingPackages(context).contains(name));
        AppMetadataSyncScheduler.acknowledge(context, newer);
        assertFalse(AppMetadataSyncScheduler.pendingPackages(context).contains(name));
        assertFalse(prefs.getBoolean(AppSourceMetadataUpdater.PREF_USE_SOURCE_DESCRIPTIONS, true));
        ((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(0x53534D44);
    }

    @Test public void schemaUpgradeKeepsBaselineHistoryAndMetadataCacheInvalidatesOnce() {
        DataHandler data = KissApplication.getApplication(context).getDataHandler();
        DBHelper.getAppSourceMetadata(context); // Open/migrate through the production DB helper.
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                context.getDatabasePath("kiss.s3db").getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM history WHERE record = ?",
                     new String[]{"test://baseline-preserved"})) {
            assertEquals(20, db.getVersion());
            assertTrue(cursor.moveToFirst());
            assertTrue("Baseline history lost during upgrade", cursor.getInt(0) > 0);
        }
        AppSourceMetadataRecord record = new AppSourceMetadataRecord();
        record.packageName = "test.verification.metadata";
        record.title = "Verification scanner";
        record.description = "Scan QR codes and EAN barcodes.";
        record.source = "Verification fixture";
        DBHelper.upsertAppSourceMetadata(context, record);
        data.invalidateAppSourceSemanticText();
        Map<String, String> first = data.getAppSourceSemanticTextByPackage();
        assertTrue(first.get(record.packageName).contains("EAN"));
        assertSame(first, data.getAppSourceSemanticTextByPackage());
        record.description = "Scan PDF documents with OCR.";
        DBHelper.upsertAppSourceMetadata(context, record);
        assertSame(first, data.getAppSourceSemanticTextByPackage());
        data.invalidateAppSourceSemanticText();
        Map<String, String> next = data.getAppSourceSemanticTextByPackage();
        assertNotSame(first, next);
        assertTrue(next.get(record.packageName).contains("OCR"));
    }

    @Test public void semanticSettingsAreReachableAndPreferenceKeysAreUnique() throws Exception {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                SettingsFragment fragment = new SettingsFragment();
                Bundle arguments = new Bundle();
                arguments.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, "semantic-search-screen");
                fragment.setArguments(arguments);
                activity.getSupportFragmentManager().beginTransaction()
                        .replace(R.id.content_container, fragment).commitNow();
                for (String key : new String[]{"semantic-search-enabled", "semantic-hnsw-enabled",
                        "semantic-hnsw-ef-search", "semantic-hnsw-rebuild", "semantic-hnsw-status",
                        "semantic-data-activity-viewer", "semantic-update-all-app-source-data"}) {
                    assertNotNull("Missing semantic setting: " + key, fragment.findPreference(key));
                }
                assertUnique(fragment.getPreferenceScreen(), new HashSet<>());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screenshot("semantic-settings.png");
        }
    }

    @Test public void hnswBuildsOffMainThreadAndSemanticOffClearsTheIndex() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            BaselineBehaviorTest.await(scenario, activity -> KissApplication.getApplication(activity)
                    .getDataHandler().isAllProvidersLoaded(), "providers for HNSW");
            prefs.edit().putBoolean("semantic-search-enabled", true)
                    .putBoolean("semantic-hnsw-enabled", true)
                    .putString("semantic-embedding-dimensions", "256").commit();
            SemanticHnswIndex index = SemanticHnswIndex.getInstance();
            index.scheduleRebuild(KissApplication.getApplication(context).getDataHandler(), prefs);
            long deadline = SystemClock.uptimeMillis() + 30_000L;
            while (!index.isReadyFor(256) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertTrue("HNSW did not build", index.isReadyFor(256));
            assertTrue(index.indexedCount() > 0);
            prefs.edit().putBoolean("semantic-search-enabled", false).commit();
            index.scheduleRebuild(KissApplication.getApplication(context).getDataHandler(), prefs);
            assertEquals(0, index.indexedCount());
            assertFalse(index.isBuilding());
        }
    }

    @Test public void dataViewerOpensAndResumesWithoutChangingAppearancePreferences() {
        int iconSize = prefs.getInt("smart-list-icon-size-percent", 210);
        try (ActivityScenario<DataActivityViewerActivity> scenario = ActivityScenario.launch(DataActivityViewerActivity.class)) {
            scenario.recreate();
            scenario.onActivity(activity -> assertEquals("Data Activity Viewer", activity.getTitle().toString()));
        }
        assertEquals(iconSize, prefs.getInt("smart-list-icon-size-percent", 210));
        assertFalse(prefs.getBoolean(AppSourceMetadataUpdater.PREF_USE_SOURCE_DESCRIPTIONS, true));
    }

    private static void assertUnique(PreferenceGroup group, Set<String> keys) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference.getKey() != null) assertTrue("Duplicate setting: " + preference.getKey(), keys.add(preference.getKey()));
            if (preference instanceof PreferenceGroup) assertUnique((PreferenceGroup) preference, keys);
        }
    }

    private void screenshot(String name) throws Exception {
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Could not capture verification screen", image);
        File directory = new File(context.getExternalFilesDir(null), "verification-screenshots");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            image.recycle();
        }
    }

    private static View findDescription(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int countDescription(View view, String description) {
        int count = description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()) ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += countDescription(group.getChildAt(i), description);
        }
        return count;
    }
}
