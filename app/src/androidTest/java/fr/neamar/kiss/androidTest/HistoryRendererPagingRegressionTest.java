package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

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
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;

/** Real Android regression for 3D Wheel, Vertical Cards and unaffected native Vertical List. */
public final class HistoryRendererPagingRegressionTest {
    @Test public void wheelAndCardsPage150RowsWithoutChangingVerticalList() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        String original = prefs.getString("smart-history-layout", "vertical");
        try {
            assertTrue(prefs.edit().putString("smart-history-layout", "wheel_3d").commit());
            try (ActivityScenario<MainActivity> scenario =
                         ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    try {
                        SearchHandler.getInstance().cancelSearch();
                        List<Result<?>> fixtures = new ArrayList<>();
                        for (int i = 0; i < 150; i++) {
                            SettingPojo pojo = new SettingPojo("setting://bounded-test-" + i,
                                    "Test setting " + i, R.drawable.setting_apps);
                            pojo.setName("History paging row " + i, true);
                            fixtures.add(Result.fromPojo(activity, pojo));
                        }
                        activity.adapter.updateResults(activity, fixtures, false, "");
                        assertEquals(150, activity.adapter.getCount());
                        Object forwarders = field(activity, "forwarderManager");
                        Object wheel = field(forwarders, "historyDisplayForwarder");
                        call(wheel, "onResume");
                        call(wheel, "rebuildWheelNow");

                        LinearLayout wheelColumn = (LinearLayout) field(wheel, "wheelColumn");
                        assertEquals("Wheel stays within its separate 12-row GPU budget", 12, wheelColumn.getChildCount());
                        TextView wheelOlder = (TextView) field(wheel, "wheelOlder");
                        assertEquals(View.VISIBLE, wheelOlder.getVisibility());
                        wheelOlder.performClick();
                        assertEquals(12, wheelColumn.getChildCount());
                        assertEquals(150, activity.adapter.getCount());
                        assertEquals(View.VISIBLE,
                                ((TextView) field(wheel, "wheelNewer")).getVisibility());

                        prefs.edit().putString("smart-history-layout", "vertical_cards").commit();
                        call(wheel, "onResume");
                        Object cards = field(forwarders, "smartCardListForwarder");
                        call(cards, "onResume");
                        LinearLayout cardColumn = (LinearLayout) field(cards, "column");
                        assertEquals("Cards must show one bounded page", 64, cardColumn.getChildCount());
                        TextView cardOlder = (TextView) field(cards, "olderCardPage");
                        assertEquals(View.VISIBLE, cardOlder.getVisibility());
                        cardOlder.performClick();
                        assertTrue(cardColumn.getChildCount() <= 64);
                        assertEquals(View.VISIBLE,
                                ((TextView) field(cards, "newerCardPage")).getVisibility());

                        prefs.edit().putString("smart-history-layout", "vertical").commit();
                        call(cards, "onResume");
                        call(wheel, "onResume");
                        assertEquals("Vertical List should be fully visible", View.VISIBLE,
                                activity.list.getVisibility());
                        assertEquals("Native Vertical List retains all results", 150,
                                activity.adapter.getCount());
                    } catch (Exception e) {
                        throw new AssertionError("History renderer paging regression", e);
                    }
                });
            }
        } finally {
            prefs.edit().putString("smart-history-layout", original).commit();
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }
    private static void call(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(owner);
    }
}
