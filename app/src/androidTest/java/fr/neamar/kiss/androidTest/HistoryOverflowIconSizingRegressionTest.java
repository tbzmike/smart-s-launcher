package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.ui.AutoMarqueeTextView;
import fr.neamar.kiss.ui.AutoScrollPreviewTextView;

/** Sizing mode must survive card retention, and compact text must actually move. */
public final class HistoryOverflowIconSizingRegressionTest {
    @Test public void compactTextActivatesMarqueeAndMovesAVisibleLongPreview() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        String oldOverflow = prefs.getString("smart-text-overflow-mode", "auto_scroll");
        String oldLayout = prefs.getString("smart-history-layout", "vertical");
        try {
            assertTrue(prefs.edit()
                    .putString("smart-text-overflow-mode", "auto_scroll")
                    .putString("smart-history-layout", "vertical_cards").commit());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                final AutoMarqueeTextView[] title = new AutoMarqueeTextView[1];
                final AutoScrollPreviewTextView[] message = new AutoScrollPreviewTextView[1];
                scenario.onActivity(activity -> {
                    FrameLayout host = activity.findViewById(android.R.id.content);
                    LinearLayout holder = new LinearLayout(activity);
                    holder.setOrientation(LinearLayout.VERTICAL);
                    holder.setBackgroundColor(0xff292929);
                    FrameLayout.LayoutParams hostParams = new FrameLayout.LayoutParams(
                            px(activity, 180), ViewGroup.LayoutParams.WRAP_CONTENT);
                    hostParams.topMargin = px(activity, 140);
                    host.addView(holder, hostParams);
                    title[0] = new AutoMarqueeTextView(activity);
                    title[0].setText("A very long social network contact title which cannot fit in the tile");
                    title[0].setTextSize(19);
                    holder.addView(title[0], new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, px(activity, 34)));
                    message[0] = new AutoScrollPreviewTextView(activity);
                    message[0].setText("First message line. A long text from the notification " +
                            "history that keeps wrapping across multiple lines, so that the " +
                            "two-line compact viewport has a great many additional lines to " +
                            "step through when Auto-scroll text is selected in Settings.");
                    message[0].setTextSize(17);
                    holder.addView(message[0], new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    title[0].refreshAutoScrollIfVisible();
                    message[0].refreshAutoScrollIfVisible();
                    assertFalse("Controlled scroll must not start another framework marquee",
                            title[0].isSelected());
                    assertNull("Full line must remain available to reveal horizontally",
                            title[0].getEllipsize());
                    assertTrue("Fixture must have more than two text lines",
                            message[0].getLayout() != null
                                    && message[0].getLayout().getLineCount() > 2);
                });
                // The emulator can spend the first seconds completing Home startup and focus
                // transfer. Verify that BOTH animations physically advance when the fixture
                // becomes visible, rather than sampling one arbitrary moment in a loop cycle.
                boolean titleMoved = false;
                boolean previewMoved = false;
                for (int sample = 0; sample < 24 && (!titleMoved || !previewMoved); sample++) {
                    SystemClock.sleep(350);
                    final boolean[] movement = new boolean[2];
                    scenario.onActivity(activity -> {
                        // Refresh is the same hook the History viewport calls on its idle event.
                        title[0].refreshAutoScrollIfVisible();
                        message[0].refreshAutoScrollIfVisible();
                        movement[0] = title[0].getScrollX() > 0;
                        movement[1] = message[0].getScrollY() > 0;
                    });
                    titleMoved |= movement[0];
                    previewMoved |= movement[1];
                }
                assertTrue("Compact title must physically move after idle", titleMoved);
                assertTrue("The compact long-text preview must physically scroll after idle",
                        previewMoved);
            }
        } finally {
            prefs.edit().putString("smart-text-overflow-mode", oldOverflow)
                    .putString("smart-history-layout", oldLayout).commit();
        }
    }

    @Test public void changingFeatureIconPercentRebindsRetainedCardsAndLeavesHistoryUnchanged() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        String oldLayout = prefs.getString("smart-history-layout", "vertical");
        Object oldPercent = prefs.getAll().get("smart-list-feature-icon-size-percent");
        boolean oldResize = prefs.getBoolean("smart-list-resize-feature-icons", true);
        try {
            assertTrue(prefs.edit()
                    .putString("smart-history-layout", "vertical_cards")
                    .putInt("smart-list-feature-icon-size-percent", 100)
                    .putBoolean("smart-list-resize-feature-icons", true).commit());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    SearchHandler.getInstance().cancelSearch();
                    List<Result<?>> results = new ArrayList<>();
                    for (int i = 0; i < 4; i++) {
                        SettingPojo item = new SettingPojo("setting://size-regression-" + i,
                                "Feature " + i, activity.getPackageName(), R.drawable.setting_apps);
                        item.setName("Icon resize fixture " + i, true);
                        results.add(Result.fromPojo(activity, item));
                    }
                    activity.adapter.updateResults(activity, results, false, "");
                });
                final int[] widths = new int[2];
                scenario.onActivity(activity -> {
                    try {
                        Object manager = field(activity, "forwarderManager");
                        Object cards = field(manager, "smartCardListForwarder");
                        call(cards, "onResume");
                        widths[0] = firstCardIconWidth((LinearLayout) field(cards, "column"));
                        assertTrue(widths[0] > 0);
                        assertTrue(prefs.edit()
                                .putInt("smart-list-feature-icon-size-percent", 210).commit());
                        call(cards, "onResume");
                        widths[1] = firstCardIconWidth((LinearLayout) field(cards, "column"));
                        assertTrue("Vertical Cards icon-size slider must change real pixels: "
                                + widths[0] + " -> " + widths[1], widths[1] > widths[0] * 1.7f);
                        assertEquals("Settings must not change the underlying History", 4,
                                activity.adapter.getCount());
                    } catch (Exception error) {
                        throw new AssertionError("Vertical Cards sizing regression", error);
                    }
                });
            }
        } finally {
            SharedPreferences.Editor restore = prefs.edit()
                    .putString("smart-history-layout", oldLayout)
                    .putBoolean("smart-list-resize-feature-icons", oldResize);
            // Real preference sliders store integers on some builds. Restore the
            // exact prior type; never cast an Integer to String in test setup.
            if (oldPercent instanceof Integer) {
                restore.putInt("smart-list-feature-icon-size-percent", (Integer) oldPercent);
            } else if (oldPercent instanceof String) {
                restore.putString("smart-list-feature-icon-size-percent", (String) oldPercent);
            } else if (oldPercent == null) {
                restore.remove("smart-list-feature-icon-size-percent");
            }
            restore.commit();
        }
    }

    private static int firstCardIconWidth(LinearLayout column) {
        assertNotNull(column);
        assertTrue(column.getChildCount() > 0);
        View first = column.getChildAt(0);
        ImageView icon = first.findViewById(R.id.item_setting_icon);
        // Some card icons have detached source view identity; fall back to
        // the visible first card ImageView without relying on a fixed icon ID.
        if (icon == null) icon = findImage(first);
        assertNotNull("Card icon missing", icon);
        assertNotNull(icon.getLayoutParams());
        return icon.getLayoutParams().width;
    }

    private static ImageView findImage(View child) {
        if (child instanceof ImageView) return (ImageView) child;
        if (!(child instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) child;
        for (int i = 0; i < group.getChildCount(); i++) {
            ImageView found = findImage(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static int px(MainActivity activity, int dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
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
