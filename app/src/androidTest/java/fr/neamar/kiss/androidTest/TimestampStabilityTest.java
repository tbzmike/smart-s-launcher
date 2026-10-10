package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.LaunchStatsProvider;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.ui.UniversalHistoryTimestamp;

/** Run against both the unfixed 166 APK and the corrected APK to prove the regressions. */
public class TimestampStabilityTest {
    @Before public void reset() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString("smart-history-layout", "vertical")
                .putInt("global-text-size-percent", 120)
                .putInt("smart-history-meta-size-sp", 11)
                .putBoolean("semantic-search-enabled", false)
                .putString("smart-text-overflow-mode", "expand").commit();
    }

    private static void ready(ActivityScenario<MainActivity> scenario) {
        BaselineBehaviorTest.await(scenario, activity -> KissApplication.getApplication(activity)
                .getDataHandler().isAllProvidersLoaded(), "providers for timestamp regression");
        scenario.onActivity(activity -> activity.displayKissBar(false));
        BaselineBehaviorTest.awaitHistory(scenario);
    }

    @Test public void metadataAppearanceSurvivesGlobalScaleAndRebinding() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            ready(scenario);
            scenario.onActivity(activity -> {
                List<Result<?>> fixtures = BaselineBehaviorTest.fixtures(activity, true);
                RecordAdapter adapter = new RecordAdapter(activity, fixtures);
                ListView parent = new ListView(activity);
                View row = adapter.getView(0, null, parent);
                for (int i = 0; i < 60; i++) {
                    row = adapter.getView(i % fixtures.size(), row, parent);
                    TextView meta = row.findViewById(R.id.item_history_meta);
                    assertEquals("metadata setting after recycled global scale", 11f,
                            meta.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity, 0.01f);
                    assertEquals(View.VISIBLE, meta.getVisibility());
                    assertEquals(1, countMetadata(row));
                }
            });
        }
    }

    @Test public void searchRowRecycledDuringTouchKeepsCorrectTimestamp() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            ready(scenario);
            AtomicReference<View> recycled = new AtomicReference<>();
            scenario.onActivity(activity -> {
                activity.searchEditText.setText("1+1");
                SearchHandler.getInstance().cancelSearch();
                List<Result<?>> fixtures = BaselineBehaviorTest.fixtures(activity, true);
                RecordAdapter adapter = new RecordAdapter(activity, fixtures);
                View row = adapter.getView(0, null, activity.list);
                UniversalHistoryTimestamp.bind(row, fixtures.get(0), activity);
                assertEquals(View.GONE, row.findViewById(R.id.item_history_meta).getVisibility());
                recycled.set(row);
                activity.searchEditText.setText("");
            });
            BaselineBehaviorTest.awaitHistory(scenario);
            scenario.onActivity(activity -> {
                List<Result<?>> fixtures = BaselineBehaviorTest.fixtures(activity, true);
                RecordAdapter adapter = new RecordAdapter(activity, fixtures);
                // Get a reference string through the normal idle bind for this exact event.
                View expectedRow = adapter.getView(1, null, new ListView(activity));
                CharSequence expected = ((TextView) expectedRow.findViewById(R.id.item_history_meta))
                        .getText().toString();
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20, 120, 0);
                MotionEvent cancel = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_CANCEL, 20, 120, 0);
                try {
                    activity.list.dispatchTouchEvent(down);
                    assertTrue(activity.list.isScrollInProgress());
                    View row = adapter.getView(1, recycled.get(), activity.list);
                    TextView meta = row.findViewById(R.id.item_history_meta);
                    assertEquals("frozen recycle timestamp visibility", View.VISIBLE, meta.getVisibility());
                    assertEquals("frozen recycle timestamp identity", expected, meta.getText().toString());
                    View fresh = adapter.getView(2, null, activity.list);
                    TextView freshMeta = fresh.findViewById(R.id.item_history_meta);
                    assertEquals("fresh scroll timestamp visibility", View.VISIBLE, freshMeta.getVisibility());
                    assertTrue(freshMeta.getText().toString().startsWith("Received "));
                    assertEquals(1, countMetadata(row));
                    assertEquals(1, countMetadata(fresh));
                } finally {
                    activity.list.dispatchTouchEvent(cancel);
                    down.recycle(); cancel.recycle();
                }
            });
        }
    }

    @Test public void invalidatedStatsKeepLastTimeUntilSnapshotPublishes() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<Result<?>> fixture = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            ready(scenario);
            scenario.onActivity(activity -> fixture.set(BaselineBehaviorTest.fixtures(activity, false).get(0)));
            Result<?> result = fixture.get();
            String id = result.getPojo().getHistoryId();
            try {
                DBHelper.insertHistory(context, "", id);
                Map<String, LaunchStatsProvider.LaunchStats> snapshot = LaunchStatsProvider.loadAll(context);
                scenario.onActivity(activity -> {
                    UniversalHistoryTimestamp.updateStats(snapshot);
                    long timestamp = UniversalHistoryTimestamp.resolveHistoryTimestamp(result);
                    assertTrue(timestamp > 0L);
                    long generation = UniversalHistoryTimestamp.statsGeneration();
                    UniversalHistoryTimestamp.invalidateStats();
                    assertEquals(generation + 1, UniversalHistoryTimestamp.statsGeneration());
                    assertEquals("invalidated time retained until replacement", timestamp,
                            UniversalHistoryTimestamp.resolveHistoryTimestamp(result));
                    UniversalHistoryTimestamp.updateStats(Collections.emptyMap());
                    assertEquals("completed replacement can remove old history", 0L,
                            UniversalHistoryTimestamp.resolveHistoryTimestamp(result));
                });
            } finally {
                DBHelper.removeFromHistory(context, id);
            }
        }
    }

    private static int countMetadata(View view) {
        int count = view.getId() == R.id.item_history_meta ? 1 : 0;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += countMetadata(group.getChildAt(i));
        }
        return count;
    }
}
