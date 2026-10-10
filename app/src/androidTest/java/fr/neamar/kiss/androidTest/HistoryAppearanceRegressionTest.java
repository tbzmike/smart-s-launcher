package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.util.List;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.UniversalHistoryTimestamp;

/** Runtime regression for 3.30.167: persisted sizing and one stable history metadata slot. */
public final class HistoryAppearanceRegressionTest {

    @Test public void historyRowsRetainSizesAndTimestampThroughRecyclingAndResume() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(target);
        assertTrue(settings.edit()
                .putBoolean("semantic-search-enabled", false)
                .putString("smart-history-layout", "vertical")
                .putInt("global-text-size-percent", 125)
                .putInt("smart-list-icon-size-percent", 100)
                .putInt("smart-list-label-size-sp", 27)
                .putInt("smart-list-body-size-sp", 18)
                .putInt("smart-history-meta-size-sp", 16)
                .commit());

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            BaselineBehaviorTest.await(scenario, activity -> KissApplication.getApplication(activity)
                    .getDataHandler().isAllProvidersLoaded(), "providers ready");
            BaselineBehaviorTest.awaitHistory(scenario);
            scenario.onActivity(activity -> {
                List<Result<?>> results = BaselineBehaviorTest.fixtures(activity, false);
                RecordAdapter adapter = new RecordAdapter(activity, results);
                ViewGroup list = activity.list;

                View app = adapter.getView(0, null, list);
                TextView appLabel = app.findViewById(R.id.item_app_name);
                assertNotNull("App name missing", appLabel);
                float initialSizePx = appLabel.getTextSize();
                float scaledSp = initialSizePx
                        / app.getResources().getDisplayMetrics().scaledDensity;
                assertEquals("Saved row label and global multiplier", 33.75f, scaledSp, 1.25f);

                ImageView appIcon = app.findViewById(R.id.item_app_icon);
                assertNotNull("App icon missing", appIcon);
                int initialWidth = effectiveIconWidth(appIcon);
                assertTrue("Baseline icon width must be positive", initialWidth > 0);

                assertTrue(activity.prefs.edit()
                        .putInt("smart-list-icon-size-percent", 220).commit());
                adapter.invalidateHistoryPresentation();
                app = adapter.getView(0, app, list);
                appIcon = app.findViewById(R.id.item_app_icon);
                int largerWidth = effectiveIconWidth(appIcon);
                assertTrue("History icon-size preference was ignored: "
                        + initialWidth + " -> " + largerWidth, largerWidth > initialWidth);

                TextView scaled = app.findViewById(R.id.item_app_name);
                float before = scaled.getTextSize();
                for (int pass = 0; pass < 8; pass++) {
                    app = adapter.getView(0, app, list);
                    scaled = app.findViewById(R.id.item_app_name);
                    assertEquals("Global scale multiplied again on recycling",
                            before, scaled.getTextSize(), 0.75f);
                    assertEquals("Icon shrank after rebinding",
                            largerWidth, effectiveIconWidth(
                                    (ImageView) app.findViewById(R.id.item_app_icon)));
                }

                View notification = adapter.getView(1, null, list);
                for (int pass = 0; pass < 8; pass++) {
                    notification = adapter.getView(pass % 2 == 0 ? 1 : 3,
                            notification, list);
                    TextView meta = notification.findViewById(R.id.item_history_meta);
                    assertNotNull("Timestamp slot disappeared after recycling", meta);
                    assertEquals(View.VISIBLE, meta.getVisibility());
                    assertTrue("Timestamp vanished after recycled notification bind",
                            meta.getText().toString().contains("Received "));
                    assertEquals(1, countId(notification, R.id.item_history_meta));
                }
            });

            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            BaselineBehaviorTest.awaitHistory(scenario);
            scenario.onActivity(activity -> {
                assertEquals(220, activity.prefs.getInt("smart-list-icon-size-percent", 0));
                assertEquals(27, activity.prefs.getInt("smart-list-label-size-sp", 0));
                assertEquals(125, activity.prefs.getInt("global-text-size-percent", 0));
                RecordAdapter adapter = new RecordAdapter(activity,
                        BaselineBehaviorTest.fixtures(activity, false));
                View row = adapter.getView(0, null, activity.list);
                ImageView icon = row.findViewById(R.id.item_app_icon);
                assertNotNull(icon);
                assertTrue("Icon config lost on resume", effectiveIconWidth(icon) > 0);
                TextView label = row.findViewById(R.id.item_app_name);
                assertNotNull(label);
                assertEquals(33.75f,
                        label.getTextSize() / row.getResources().getDisplayMetrics().scaledDensity,
                        1.25f);
            });
        }
    }

    private static int effectiveIconWidth(ImageView icon) {
        View parent = icon.getParent() instanceof View ? (View) icon.getParent() : icon;
        ViewGroup.LayoutParams parentParams = parent.getLayoutParams();
        if (parentParams != null && parentParams.width > 0 && parentParams.height > 0)
            return parentParams.width;
        ViewGroup.LayoutParams params = icon.getLayoutParams();
        return params == null ? 0 : params.width;
    }

    private static int countId(View root, int id) {
        int count = root.getId() == id ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += countId(group.getChildAt(i), id);
            }
        }
        return count;
    }
}
