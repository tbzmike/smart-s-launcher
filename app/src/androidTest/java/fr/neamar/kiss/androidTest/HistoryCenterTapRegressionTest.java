package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.utils.UserHandle;

/** The centre of each History row must dispatch exactly one app/shortcut launch. */
public final class HistoryCenterTapRegressionTest {
    static final class CapturingAdapter extends RecordAdapter {
        int launches;
        int longPresses;
        String lastId;
        CapturingAdapter(MainActivity owner, List<Result<?>> results) {
            super(owner, results);
        }
        @Override public void onClick(int position, View source) {
            launches++;
            lastId = getItem(position).getPojoId();
        }
        @Override public void onLongClick(int position, View source) {
            longPresses++;
        }
    }

    @Test public void centreTapOpensAppsUserShortcutsAndSystemShortcutsInAllThreeLayouts() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        String oldMode = prefs.getString("smart-history-layout", "vertical");
        try {
            assertTrue(prefs.edit().putString("smart-history-layout", "vertical").commit());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    try {
                        SearchHandler.getInstance().cancelSearch();
                        List<Result<?>> fixtures = new ArrayList<>(
                                BaselineBehaviorTest.fixtures(activity, false));
                        UserHandle user = new UserHandle(activity,
                                android.os.Process.myUserHandle());
                        AppPojo app = new AppPojo("app://centre-test", activity.getPackageName(),
                                MainActivity.class.getName(), user, false, false, false, false);
                        app.setName("Centre tap app");
                        app.setTags("Launch me");
                        fixtures.add(Result.fromPojo(activity, app));

                        ShortcutRecord record = new ShortcutRecord();
                        record.name = "Centre tap user shortcut";
                        record.packageName = activity.getPackageName();
                        record.intentUri = "intent:#Intent;action=android.intent.action.VIEW;end";
                        ShortcutPojo shortcut = new ShortcutPojo(UserHandle.OWNER, record,
                                null, true, false, false);
                        shortcut.setTags("Open scanner");
                        fixtures.add(Result.fromPojo(activity, shortcut));

                        SettingPojo system = new SettingPojo("setting://centre-system-action",
                                "Centre action", activity.getPackageName(), R.drawable.setting_apps);
                        system.setName("Centre tap system shortcut", true);
                        system.setTags("Settings tag");
                        fixtures.add(Result.fromPojo(activity, system));

                        CapturingAdapter adapter = new CapturingAdapter(activity, fixtures);
                        activity.adapter = adapter;
                        activity.list.setAdapter(adapter);
                        int firstTarget = fixtures.size() - 3;
                        int[] labelIds = {R.id.item_app_name, R.id.item_app_name,
                                R.id.item_setting_name};

                        // Native Vertical List and every recycled centre label use the shared
                        // adapter actions, not only its icon/outer container.
                        for (int i = 0; i < 3; i++) {
                            int position = firstTarget + i;
                            View row = adapter.getView(position, null, activity.list);
                            View center = row.findViewById(labelIds[i]);
                            assertNotNull("Vertical List centre missing", center);
                            tap(center);
                            assertLaunch(adapter, fixtures.get(position).getPojoId(), i + 1);
                            if (i == 0) {
                                assertTrue("Long press must still be available",
                                        center.performLongClick());
                                assertEquals(1, adapter.longPresses);
                            }
                        }

                        Object manager = field(activity, "forwarderManager");
                        Object wheel = field(manager, "historyDisplayForwarder");
                        assertTrue(prefs.edit().putString("smart-history-layout", "wheel_3d").commit());
                        call(wheel, "onResume");
                        call(wheel, "rebuildWheelNow");
                        LinearLayout wheelColumn = (LinearLayout) field(wheel, "wheelColumn");
                        assertTrue("3D Wheel should have recent History", wheelColumn.getChildCount() >= 3);
                        for (int i = 0; i < 3; i++) {
                            int position = firstTarget + i;
                            int local = wheelColumn.getChildCount() - 3 + i;
                            FrameLayout shell = (FrameLayout) wheelColumn.getChildAt(local);
                            View row = shell.getChildAt(0);
                            View label = row.findViewById(labelIds[i]);
                            assertNotNull("3D Wheel centre missing", label);
                            tap(label);
                            assertLaunch(adapter, fixtures.get(position).getPojoId(), 4 + i);
                        }

                        assertTrue(prefs.edit().putString("smart-history-layout", "vertical_cards").commit());
                        call(wheel, "onResume");
                        Object cards = field(manager, "smartCardListForwarder");
                        call(cards, "onResume");
                        LinearLayout cardColumn = (LinearLayout) field(cards, "column");
                        assertTrue("Vertical Cards should have recent History", cardColumn.getChildCount() >= 3);
                        for (int i = 0; i < 3; i++) {
                            int position = firstTarget + i;
                            ViewGroup wrapper = (ViewGroup) cardColumn.getChildAt(
                                    cardColumn.getChildCount() - 3 + i);
                            ViewGroup card = (ViewGroup) wrapper.getChildAt(0);
                            ViewGroup mainRow = (ViewGroup) card.getChildAt(0);
                            ViewGroup center = (ViewGroup) mainRow.getChildAt(1);
                            assertTrue("Card centre has label", center.getChildCount() > 0);
                            View title = center.getChildAt(0);
                            tap(title);
                            assertLaunch(adapter, fixtures.get(position).getPojoId(), 7 + i);
                            if (center.getChildCount() > 1) {
                                tap(center.getChildAt(1));
                                assertLaunch(adapter, fixtures.get(position).getPojoId(), 8 + i * 2);
                            }
                        }
                    } catch (Exception error) {
                        throw new AssertionError("Centre click regression", error);
                    }
                });
            }
        } finally {
            prefs.edit().putString("smart-history-layout", oldMode).commit();
        }
    }

    private static void tap(View target) {
        assertTrue("Centre text must have a click handler", target.hasOnClickListeners());
        // Direct View dispatch exercises ACTION_DOWN/UP consumption, not just listener presence.
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10, 10, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 75, MotionEvent.ACTION_UP, 10, 10, 0);
        try {
            assertTrue("Centre should accept DOWN", target.dispatchTouchEvent(down));
            assertTrue("Centre should accept UP", target.dispatchTouchEvent(up));
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static void assertLaunch(CapturingAdapter adapter, String expectedId, int count) {
        assertEquals("Centre must trigger exactly one launch", count, adapter.launches);
        assertEquals("Wrong app or shortcut target", expectedId, adapter.lastId);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    private static void call(Object owner, String name) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(owner);
    }
}
