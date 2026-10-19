package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.view.animation.AnimationUtils;
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

/** Regression against Card inflation bursts, page loss and redundant resume redraws. */
public final class VerticalCardsPerformanceRegressionTest {
    @Test public void hundredsOfHistoryEntriesAttachOnly24CardsAndReuseUnchangedViews() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String old = prefs.getString("smart-history-layout", "vertical");
        try {
            assertTrue(prefs.edit().putString("smart-history-layout", "vertical_cards").commit());
            try(ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    try {
                        SearchHandler.getInstance().cancelSearch();
                        List<Result<?>> results = new ArrayList<>();
                        for(int n=0; n<260; n++) {
                            SettingPojo pojo = new SettingPojo(
                                "setting://perf-"+n, "Perf "+n,
                                R.drawable.setting_apps);
                            pojo.setName("Card performance regression "+n, true);
                            results.add(Result.fromPojo(activity, pojo));
                        }
                        activity.adapter.updateResults(activity, results, false, "");
                        Object forwarders = field(activity, "forwarderManager");
                        Object cards = field(forwarders, "smartCardListForwarder");
                        call(cards, "onResume");
                        LinearLayout column = (LinearLayout) field(cards, "column");
                        assertEquals("Only 24 cards should consume draw/layout work",24,column.getChildCount());
                        View retained = column.getChildAt(0);
                        for(int repeat=0; repeat<5; repeat++) {
                            call(cards, "onResume");
                            assertEquals("No additional card inflation on repeated Home",24,column.getChildCount());
                            assertSame("Stable Home must reuse existing Card tree",
                                    retained, column.getChildAt(0));
                        }
                        assertEquals("Underlying History remains complete",260,activity.adapter.getCount());
                        View older = (View) field(cards,"olderCardPage");
                        assertEquals(View.VISIBLE,older.getVisibility());
                        older.performClick();
                        assertEquals(24,column.getChildCount());
                        assertEquals(260,activity.adapter.getCount());
                    } catch(Exception e) {
                        throw new AssertionError("Card performance regression",e);
                    }
                });
            }
        } finally {
            prefs.edit().putString("smart-history-layout",old).commit();
        }
    }
    @Test public void sidewaysGoogleFallbackAnimationsHaveValidAndroidResources() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotNull(AnimationUtils.loadAnimation(context,R.anim.discover_slide_in_from_left));
        assertNotNull(AnimationUtils.loadAnimation(context,R.anim.discover_home_out_right));
    }
    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }
    private static void call(Object owner,String name) throws Exception {
        Method m=owner.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(owner);
    }
}
