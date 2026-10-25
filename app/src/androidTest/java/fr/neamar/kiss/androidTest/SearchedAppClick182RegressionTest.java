package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Process;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.UserHandle;

/**
 * Regresses searched-app taps while the immediate preview and delayed database search
 * replace Result objects. Neither an out-of-range adapter index nor launch animation
 * is permitted to swallow the selected app's click.
 */
public final class SearchedAppClick182RegressionTest {
    private static final class TrackingAdapter extends RecordAdapter {
        int directCount;
        int indexedCount;
        String selectedId;

        TrackingAdapter(MainActivity activity, List<Result<?>> results) {
            super(activity, results);
        }
        @Override public void onClick(int index, View source) {
            indexedCount++;
            onResultClick(getItem(index), source);
        }
        @Override protected void onResultClick(Result<?> result, View source) {
            directCount++;
            selectedId = result.getPojoId();
        }
    }

    @Test public void tappingReplacedSearchResultStillDispatchesCorrectApp() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                // Search is active, before the five-second global database stage.
                SearchHandler.getInstance().search(Searcher.Type.QUERY,
                        activity, "searchtap182", false);

                UserHandle user = new UserHandle(activity, Process.myUserHandle());
                AppPojo pojo = new AppPojo("app://searched-click-182",
                        activity.getPackageName(), MainActivity.class.getName(),
                        user, false, false, false, false);
                pojo.setName("Search click 182");
                List<Result<?>> list = new ArrayList<>();
                list.add(Result.fromPojo(activity, pojo));
                TrackingAdapter adapter = new TrackingAdapter(activity, list);
                View row = adapter.getView(0, null, activity.list);
                View title = row.findViewById(R.id.item_app_name);
                assertNotNull(title);

                // Normal query row uses standard launch selection.
                assertTrue(title.performClick());
                assertEquals(1, adapter.indexedCount);
                assertEquals(1, adapter.directCount);
                assertEquals(pojo.id, adapter.selectedId);

                // A pending preview publication replaced the adapter while the
                // finger was touching the old row: launch its tapped identity anyway.
                list.clear();
                assertTrue(title.performClick());
                assertEquals(1, adapter.indexedCount);
                assertEquals(2, adapter.directCount);
                assertEquals(pojo.id, adapter.selectedId);
                SearchHandler.getInstance().cancelSearch();
            });
        }
    }
}
