package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.ui.AutoMarqueeTextView;
import fr.neamar.kiss.ui.AutoScrollPreviewTextView;
import fr.neamar.kiss.ui.HistoryTextScrollActivator;

/** Checks real pixel movement, not merely the selected Auto Scroll preference. */
public final class HistoryAutoScrollMotionRegressionTest {
    private static final String LONG_LABEL =
            "WhatsApp: Andronicah Moiketsi Mahlangu — a deliberately very long " +
            "label that cannot fit inside a compact History card or tile";
    private static final String LONG_PREVIEW =
            "This is a very long notification preview for the History search result. " +
            "The selected Auto Scroll mode must move through all of the wrapped " +
            "message rather than leaving the first two lines permanently still. " +
            "Additional content confirms that more than two lines exist.";

    @Test public void compactTextActuallyMovesThenExpandStopsTimers() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        String priorMode = prefs.getString("smart-text-overflow-mode", "auto_scroll");
        String priorLayout = prefs.getString("smart-history-layout", "vertical");
        try {
            assertTrue(prefs.edit()
                    .putString("smart-history-layout", "vertical_cards")
                    .putString("smart-text-overflow-mode", "auto_scroll").commit());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                AutoMarqueeTextView[] label = new AutoMarqueeTextView[1];
                AutoScrollPreviewTextView[] preview = new AutoScrollPreviewTextView[1];
                View[] host = new View[1];
                scenario.onActivity(activity -> {
                    int width = dp(activity, 160);
                    LinearLayout column = new LinearLayout(activity);
                    column.setOrientation(LinearLayout.VERTICAL);
                    column.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4));
                    column.setBackgroundColor(Color.DKGRAY);
                    host[0] = column;
                    AutoMarqueeTextView title = new AutoMarqueeTextView(activity);
                    title.setText(LONG_LABEL);
                    title.setTextColor(Color.WHITE);
                    title.setTextSize(19);
                    label[0] = title;
                    column.addView(title, new LinearLayout.LayoutParams(
                            width, dp(activity, 32)));
                    AutoScrollPreviewTextView text = new AutoScrollPreviewTextView(activity);
                    text.setText(LONG_PREVIEW);
                    text.setTextColor(Color.WHITE);
                    text.setTextSize(17);
                    preview[0] = text;
                    column.addView(text, new LinearLayout.LayoutParams(
                            width, dp(activity, 52)));
                    ((FrameLayout) activity.findViewById(android.R.id.content)).addView(
                            column, new FrameLayout.LayoutParams(
                                    width + dp(activity, 8), dp(activity, 110)));
                    column.bringToFront();
                    HistoryTextScrollActivator.refresh(column);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    HistoryTextScrollActivator.refresh(host[0]);
                    assertTrue("Label should have horizontal overflow",
                            label[0].getPaint().measureText(LONG_LABEL)
                                    > label[0].getWidth() * 1.5f);
                    assertTrue("Preview must contain more than two lines",
                            preview[0].getLayout() != null
                                    && preview[0].getLayout().getLineCount() > 2);
                });
                Thread.sleep(3400L);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    assertTrue("Auto Scroll selected but compact title never moved",
                            label[0].getScrollX() > 0);
                    assertTrue("Auto Scroll selected but notification preview never advanced",
                            preview[0].getScrollY() > 0);
                });

                assertTrue(prefs.edit().putString("smart-text-overflow-mode", "auto_expand").commit());
                scenario.onActivity(activity -> {
                    label[0].refreshConfiguredBehavior();
                    preview[0].refreshConfiguredBehavior();
                    label[0].requestLayout();
                    preview[0].requestLayout();
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    assertEquals("Auto Expand must reset horizontal motion", 0, label[0].getScrollX());
                    assertEquals("Auto Expand must reset vertical motion", 0, preview[0].getScrollY());
                    assertEquals("Auto Expand must not truncate marquee label",
                            Integer.MAX_VALUE, label[0].getMaxLines());
                    assertEquals("Auto Expand must not truncate preview",
                            Integer.MAX_VALUE, preview[0].getMaxLines());
                });
                Thread.sleep(1500L);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    assertEquals(0, label[0].getScrollX());
                    assertEquals(0, preview[0].getScrollY());
                    ((FrameLayout) activity.findViewById(android.R.id.content)).removeView(host[0]);
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            prefs.edit().putString("smart-text-overflow-mode", priorMode)
                    .putString("smart-history-layout", priorLayout).commit();
        }
    }

    private static int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
