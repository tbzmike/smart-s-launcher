package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.SystemClock;
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

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.DataActivityViewerActivity;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.SettingsActivity;
import fr.neamar.kiss.SettingsFragment;
import fr.neamar.kiss.db.AppSourceMetadataRecord;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.searcher.AppMetadataSyncScheduler;
import fr.neamar.kiss.searcher.AppSourceMetadataUpdater;
import fr.neamar.kiss.searcher.SemanticHnswIndex;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.ui.ChronologicalDateScroller;

public class FeaturePortBehaviorTest {
    private Context context;
    private SharedPreferences prefs;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean("semantic-search-enabled", false)
                .putBoolean(AppSourceMetadataUpdater.PREF_USE_SOURCE_DESCRIPTIONS, false)
                .putString("smart-history-layout", "vertical").commit();
        ((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(0x53534D44);
    }

    @Test public void dateControlHasOneOwnerAndDoesNotCoverHistoryRows() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            BaselineBehaviorTest.awaitHistory(scenario);
            scenario.onActivity(activity -> {
                SearchHandler.getInstance().cancelSearch();
                activity.adapter.updateResults(activity, BaselineBehaviorTest.fixtures(activity, true), false, "");
            });
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
        }
    }

    @Test public void chronologicalHeaderRestoresNonChronologicalLayoutAndDoesNotDuplicateViews() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FrameLayout host = new FrameLayout(context);
            ListView content = new ListView(context);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
            params.topMargin = 7;
            host.addView(content, params);
            ChronologicalDateScroller scroller = new ChronologicalDateScroller(context, host);
            for (int i = 0; i < 20; i++) {
                scroller.setSource(40, position -> System.currentTimeMillis(), (position, offset) -> {});
            }
            assertEquals(3, host.getChildCount());
            assertTrue(((FrameLayout.LayoutParams) content.getLayoutParams()).topMargin > 7);
            scroller.setSource(0, null, null);
            assertEquals(7, ((FrameLayout.LayoutParams) content.getLayoutParams()).topMargin);
            assertEquals(View.GONE, host.getChildAt(1).getVisibility());
            assertEquals(View.GONE, host.getChildAt(2).getVisibility());
        });
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

    @Test public void semanticSettingsAreReachableAndPreferenceKeysAreUnique() {
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
