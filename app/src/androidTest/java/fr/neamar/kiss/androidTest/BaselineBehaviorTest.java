package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.SearchPojo;
import fr.neamar.kiss.pojo.SearchPojoType;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.UserHandle;

/** Runs unchanged against 3.30.153 and 3.30.166; CI compares the rendered measurements. */
public class BaselineBehaviorTest {
    @Before public void resetProfile() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Keep the rendering comparison independent of 153's known cold-cache mkdir race.
        for (String name : new String[]{"icons", "custom_icons"}) {
            File directory = new File(context.getCacheDir(), name);
            assertTrue(directory.isDirectory() || directory.mkdirs() || directory.isDirectory());
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(prefs.edit().clear()
                .putBoolean("semantic-search-enabled", false)
                .putBoolean("enable-app-usage-tracking", false)
                .putString("smart-history-layout", "vertical")
                .putInt("global-text-size-percent", 120)
                .putInt("smart-list-icon-size-percent", 210)
                .putInt("smart-list-notification-icon-size-percent", 150)
                .putInt("smart-list-label-size-sp", 21)
                .putInt("smart-list-body-size-sp", 13)
                .putInt("smart-history-meta-size-sp", 11)
                .putString("smart-text-overflow-mode", "expand")
                .commit());
        KissApplication.getApplication(context).getDataHandler().clearHistory();
    }

    @Test public void baselineRenderingAndLifecycleFingerprint() throws Exception {
        JSONObject measurements = new JSONObject();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(scenario, activity -> KissApplication.getApplication(activity)
                    .getDataHandler().isAllProvidersLoaded(), "provider loading");
            awaitHistory(scenario);
            capture(scenario, measurements, "initial", false);

            scenario.onActivity(activity -> activity.searchEditText.setText("1+1"));
            await(scenario, activity -> SearchHandler.getInstance().getLastSearchType() == Searcher.Type.QUERY
                    && containsCalculator(activity), "calculator search");
            scenario.onActivity(activity -> activity.searchEditText.setText(""));
            awaitHistory(scenario);
            capture(scenario, measurements, "after_search", false);

            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitHistory(scenario);
            capture(scenario, measurements, "after_resume", false);

            capture(scenario, measurements, "during_scroll", true);
            scenario.recreate();
            awaitHistory(scenario);
            capture(scenario, measurements, "after_recreate", false);

            scenario.onActivity(activity -> {
                assertEquals(210, activity.prefs.getInt("smart-list-icon-size-percent", 0));
                assertEquals(21, activity.prefs.getInt("smart-list-label-size-sp", 0));
                assertEquals(120, activity.prefs.getInt("global-text-size-percent", 0));
                KissApplication.getApplication(activity).getDataHandler()
                        .addToHistory("test://baseline-preserved");
            });
        }
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File output = new File(context.getExternalFilesDir(null), "baseline-behavior.json");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(measurements.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    static void awaitHistory(ActivityScenario<MainActivity> scenario) {
        await(scenario, activity -> SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY,
                "history surface");
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    static void await(ActivityScenario<MainActivity> scenario, Predicate<MainActivity> condition,
                      String description) {
        long deadline = SystemClock.uptimeMillis() + 30_000L;
        AtomicBoolean ready = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> ready.set(condition.test(activity)));
            if (ready.get()) return;
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static boolean containsCalculator(MainActivity activity) {
        for (int i = 0; i < activity.adapter.getCount(); i++) {
            Pojo pojo = activity.adapter.getItem(i).getPojo();
            if (pojo instanceof SearchPojo) {
                SearchPojo search = (SearchPojo) pojo;
                if (search.type == SearchPojoType.CALCULATOR_QUERY
                        && search.query != null && search.query.contains("= 2")) return true;
            }
        }
        return false;
    }

    static List<Result<?>> fixtures(MainActivity activity, boolean notificationsOnly) {
        List<Result<?>> fixtures = new ArrayList<>();
        UserHandle user = new UserHandle(activity, android.os.Process.myUserHandle());
        for (int i = 0; i < 24; i++) {
            Pojo pojo;
            if (notificationsOnly || (i & 1) == 1) {
                pojo = new NotificationPojo("notification://verification-" + i, activity.getPackageName(),
                        "Verification app " + i, "", "verification-" + i, 1,
                        "A long notification title which must retain the configured history text size",
                        "Full notification body with several words for testing recycled rows.",
                        System.currentTimeMillis() - (24L - i) * 3_600_000L);
            } else {
                AppPojo app = new AppPojo("app://verification/" + i, activity.getPackageName(),
                        MainActivity.class.getName(), user, false, false, false, false);
                app.setName("Verification app " + i);
                app.setTags("test tag");
                pojo = app;
            }
            fixtures.add(Result.fromPojo(activity, pojo));
        }
        return fixtures;
    }

    private static void capture(ActivityScenario<MainActivity> scenario, JSONObject output,
                                String stage, boolean scrolling) {
        scenario.onActivity(activity -> {
            try {
                List<Result<?>> fixtures = fixtures(activity, false);
                SearchHandler.getInstance().cancelSearch();
                RecordAdapter adapter = new RecordAdapter(activity, fixtures);
                FrameLayout parent = new FrameLayout(activity);
                int width = activity.getResources().getDisplayMetrics().widthPixels;
                parent.layout(0, 0, width, 1600);
                if (scrolling) {
                    activity.adapter.updateResults(activity, fixtures, false, "");
                    long now = SystemClock.uptimeMillis();
                    MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20, 500, 0);
                    MotionEvent move = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_MOVE, 20, 120, 0);
                    activity.list.dispatchTouchEvent(down);
                    activity.list.dispatchTouchEvent(move);
                    down.recycle(); move.recycle();
                }
                JSONArray rows = new JSONArray();
                Map<Integer, View> recycled = new HashMap<>();
                for (int pass = 0; pass < 3; pass++) {
                    for (int i = 0; i < fixtures.size(); i++) {
                        int type = adapter.getItemViewType(i);
                        View row = adapter.getView(i, recycled.get(type), scrolling ? activity.list : parent);
                        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        row.layout(0, 0, width, row.getMeasuredHeight());
                        recycled.put(type, row);
                        if (i < 2 || i >= fixtures.size() - 2) {
                            JSONObject sample = new JSONObject();
                            sample.put("pass", pass); sample.put("position", i);
                            sample.put("app_label", textSize(row, R.id.item_app_name));
                            sample.put("app_tag", textSize(row, R.id.item_app_tag));
                            sample.put("notification_label", textSize(row, R.id.item_notification_app));
                            sample.put("notification_body", textSize(row, R.id.item_notification_text));
                            int iconId = (i & 1) == 0 ? R.id.item_app_icon : R.id.item_notification_icon;
                            ImageView icon = row.findViewById(iconId);
                            assertTrue("Missing icon for fixture " + i, icon != null);
                            sample.put("icon_width", icon.getLayoutParams().width);
                            sample.put("icon_height", icon.getLayoutParams().height);
                            rows.put(sample);
                        }
                    }
                }
                if (scrolling) {
                    long now = SystemClock.uptimeMillis();
                    MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 20, 120, 0);
                    activity.list.dispatchTouchEvent(cancel); cancel.recycle();
                }
                output.put(stage, rows);
            } catch (Exception error) {
                throw new AssertionError(stage, error);
            }
        });
    }

    private static double textSize(View row, int id) {
        TextView text = row.findViewById(id);
        return text == null ? -1 : Math.round(text.getTextSize()
                / text.getResources().getDisplayMetrics().scaledDensity * 1000d) / 1000d;
    }
}
