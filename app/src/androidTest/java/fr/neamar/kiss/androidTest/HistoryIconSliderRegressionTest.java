package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.pojo.ContactsPojo;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.utils.UserHandle;

public final class HistoryIconSliderRegressionTest {
    @Test public void cardsApplyStoredNotificationSliderToActualImageAndRebindImmediately() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        String original = prefs.getString("smart-history-layout", "vertical");
        try {
            assertTrue(prefs.edit().putString("smart-history-layout", "vertical_cards")
                    .putInt("smart-list-notification-icon-size-percent", 100)
                    .putInt("smart-list-card-icon-percent", 100)
                    .putBoolean("smart-list-resize-notification-icons", true).commit());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    try {
                        SearchHandler.getInstance().cancelSearch();
                        List<Result<?>> fixtures = BaselineBehaviorTest.fixtures(activity, false);
                        activity.adapter.updateResults(activity, fixtures, false, "");
                        Object forwarders = field(activity, "forwarderManager");
                        Object cards = field(forwarders, "smartCardListForwarder");
                        invoke(cards, "onResume");
                        invoke(cards, "rebuildImmediately");
                        LinearLayout column = (LinearLayout) field(cards, "column");
                        assertEquals(24, column.getChildCount());

                        ImageView first = cardIcon(column, 23);
                        assertEquals(ImageView.ScaleType.FIT_CENTER, first.getScaleType());
                        int initial = first.getLayoutParams().width;
                        int expectedInitial = dp(activity, 66);
                        assertTrue("Baseline icon is near configured 100%",
                                Math.abs(initial - expectedInitial) <= dp(activity, 2));

                        assertTrue(prefs.edit()
                                .putInt("smart-list-notification-icon-size-percent", 206).commit());
                        invoke(cards, "onResume");
                        ImageView resized = cardIcon(column, 23);
                        int actual = resized.getLayoutParams().width;
                        assertEquals("206% must enlarge the actual photo ImageView, not an empty box",
                                dp(activity, 136), actual);
                        assertTrue("206% should visibly exceed initial icon size", actual > initial);
                        assertEquals(ImageView.ScaleType.FIT_CENTER, resized.getScaleType());

                        assertTrue(prefs.edit()
                                .putBoolean("smart-list-resize-notification-icons", false).commit());
                        invoke(cards, "onResume");
                        assertEquals("Switch OFF must restore 100% immediately",
                                expectedInitial, cardIcon(column, 23).getLayoutParams().width);

                        // Native List & Wheel continue to own their own sizing paths.
                        assertEquals(24, activity.adapter.getCount());
                    } catch (Exception error) {
                        throw new AssertionError("Vertical Cards icon slider wiring", error);
                    }
                });
            }
        } finally {
            prefs.edit().putString("smart-history-layout", original)
                    .remove("smart-list-notification-icon-size-percent")
                    .remove("smart-list-card-icon-percent")
                    .remove("smart-list-resize-notification-icons").commit();
        }
    }

    @Test public void categorySlidersApplyToContactSystemAndUserShortcutsIndependently() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        String original = prefs.getString("smart-history-layout", "vertical");
        try {
            assertTrue(prefs.edit().putString("smart-history-layout", "vertical_cards")
                    .putInt("smart-list-card-icon-percent", 100)
                    .putInt("smart-list-shortcut-icon-size-percent", 181)
                    .putInt("smart-list-feature-icon-size-percent", 163)
                    .putInt("smart-list-contact-icon-size-percent", 210)
                    .putBoolean("smart-list-resize-shortcut-icons", true)
                    .putBoolean("smart-list-resize-feature-icons", true)
                    .putBoolean("smart-list-resize-contact-icons", true).commit());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    try {
                        Object manager = field(activity, "forwarderManager");
                        Object cards = field(manager, "smartCardListForwarder");
                        Method percent = cards.getClass().getDeclaredMethod("configuredCardIconPercent", Result.class);
                        percent.setAccessible(true);

                        ShortcutRecord record = new ShortcutRecord();
                        record.name = "Scan barcode";
                        record.packageName = activity.getPackageName();
                        record.intentUri = "intent:#Intent;action=android.intent.action.VIEW;end";
                        ShortcutPojo shortcut = new ShortcutPojo(UserHandle.OWNER, record,
                                null, true, false, false);
                        SettingPojo setting = new SettingPojo("setting://slider-check", "Settings",
                                activity.getPackageName(), fr.neamar.kiss.R.drawable.setting_apps);
                        setting.setName("Settings", true);
                        ContactsPojo contact = new ContactsPojo("contact://slider", "test",
                                1, null, true, false);
                        contact.setName("Avatar contact");
                        assertEquals(181, ((Integer) percent.invoke(cards,
                                Result.fromPojo(activity, shortcut))).intValue());
                        assertEquals(163, ((Integer) percent.invoke(cards,
                                Result.fromPojo(activity, setting))).intValue());
                        assertEquals(210, ((Integer) percent.invoke(cards,
                                Result.fromPojo(activity, contact))).intValue());
                    } catch (Exception error) {
                        throw new AssertionError("Icon category preference wiring", error);
                    }
                });
            }
        } finally {
            prefs.edit().putString("smart-history-layout", original)
                    .remove("smart-list-card-icon-percent")
                    .remove("smart-list-shortcut-icon-size-percent")
                    .remove("smart-list-feature-icon-size-percent")
                    .remove("smart-list-contact-icon-size-percent")
                    .remove("smart-list-resize-shortcut-icons")
                    .remove("smart-list-resize-feature-icons")
                    .remove("smart-list-resize-contact-icons").commit();
        }
    }

    private static ImageView cardIcon(LinearLayout column, int position) {
        ViewGroup wrapper = (ViewGroup) column.getChildAt(position);
        ViewGroup card = (ViewGroup) wrapper.getChildAt(0);
        ViewGroup row = (ViewGroup) card.getChildAt(0);
        return (ImageView) row.getChildAt(0);
    }
    private static int dp(MainActivity activity, int dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
    private static void invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(target);
    }
}
