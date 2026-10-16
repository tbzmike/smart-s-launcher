package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.view.Gravity;
import android.widget.TextView;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.util.List;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.AutoMarqueeTextView;
import fr.neamar.kiss.ui.AutoScrollPreviewTextView;
import fr.neamar.kiss.ui.ChronologicalDateScroller;

public final class HistoryLongTextRegressionTest {
    private static final String LONG_MESSAGE =
            "Maps: How was Willows Crossing Shopping Centre? Congratulations! "
            + "You have ranked in the top twenty percent of Stores and shopping "
            + "reviewers on Google. Your review was read many times today. "
            + "This is a deliberately long notification title and description.";

    @Test public void oneRetainedTextViewSwitchesBetweenFullWrapAndCompactScroll() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(prefs.edit()
                .putString("smart-history-layout", "vertical")
                .putString("smart-text-overflow-mode", "auto_expand").commit());

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                AutoMarqueeTextView text = new AutoMarqueeTextView(activity);
                text.setText(LONG_MESSAGE);
                int px = Math.round(140 * activity.getResources().getDisplayMetrics().density);
                measure(text, px);
                assertTrue("Auto-expand should wrap more than one line", text.getLineCount() > 2);
                assertEquals("Auto-expand must not truncate the text", null, text.getEllipsize());

                AutoScrollPreviewTextView message = new AutoScrollPreviewTextView(activity);
                message.setText(LONG_MESSAGE);
                measure(message, px);
                assertTrue("Auto-expand preview must grow beyond two lines",
                        message.getMeasuredHeight() >= message.getLineHeight() * 3);

                assertTrue(prefs.edit().putString("smart-text-overflow-mode", "auto_scroll").commit());
                assertEquals("Saved preference must change", "auto_scroll",
                        prefs.getString("smart-text-overflow-mode", ""));
                assertEquals("Activity must resolve effective mode", "auto_scroll",
                        fr.neamar.kiss.ui.TextOverflowMode.effectiveMode(activity));
                text.refreshConfiguredBehavior();
                measure(text, px);
                // TextView can retain the old multi-line Layout object even as
                // maxLines and measured compact height have already switched.
                assertEquals("Compact marquee must enforce one visible line",
                        1, text.getMaxLines());
                assertEquals("Custom deterministic overflow scroll does not use Android's focus-only marquee",
                        null, text.getEllipsize());
                assertTrue("Long text must not keep the old expanded tile height",
                        text.getMeasuredHeight() <= text.getLineHeight() * 2);

                message.refreshConfiguredBehavior();
                measure(message, px);
                int twoLineViewport = message.getLineHeight() * 2
                        + message.getCompoundPaddingTop() + message.getCompoundPaddingBottom();
                assertTrue("Compact preview must fit two lines plus configured padding: "
                        + message.getMeasuredHeight() + " > " + twoLineViewport,
                        message.getMeasuredHeight() <= twoLineViewport);

                assertTrue(prefs.edit().putString("smart-text-overflow-mode", "auto_expand").commit());
                // Detached fixture views have no Settings/onResume notification. Real
                // History rows are explicitly rebound by the adapter on this change.
                // Trigger that same presentation binding in both directions.
                text.refreshConfiguredBehavior();
                measure(text, px);
                assertTrue("Switching back must restore every wrapped line", text.getLineCount() > 2);
                message.refreshConfiguredBehavior();
                measure(message, px);
                assertTrue("Switching back restores full preview height",
                        message.getMeasuredHeight() >= message.getLineHeight() * 3);
            });
        }
    }

    @Test public void autoScrollReallyMovesTextInAllThreeLayoutsAndExpandStopsMotion() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String oldLayout = prefs.getString("smart-history-layout", "vertical");
        String oldMode = prefs.getString("smart-text-overflow-mode", "auto_scroll");
        final String[] layouts = {"vertical", "vertical_cards", "wheel_3d"};
        try {
            assertTrue(prefs.edit().putString("smart-text-overflow-mode", "auto_scroll").commit());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                final AutoMarqueeTextView[] title = new AutoMarqueeTextView[1];
                final AutoScrollPreviewTextView[] preview = new AutoScrollPreviewTextView[1];
                final int[] px = new int[1];
                scenario.onActivity(activity -> {
                    px[0] = Math.round(145 * activity.getResources().getDisplayMetrics().density);
                    FrameLayout root = activity.findViewById(android.R.id.content);
                    LinearLayout fixture = new LinearLayout(activity);
                    fixture.setOrientation(LinearLayout.VERTICAL);
                    FrameLayout.LayoutParams fixtureLp = new FrameLayout.LayoutParams(
                            px[0], ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
                    root.addView(fixture, fixtureLp);
                    title[0] = new AutoMarqueeTextView(activity);
                    title[0].setText(LONG_MESSAGE);
                    fixture.addView(title[0], new LinearLayout.LayoutParams(
                            px[0], ViewGroup.LayoutParams.WRAP_CONTENT));
                    preview[0] = new AutoScrollPreviewTextView(activity);
                    preview[0].setText(LONG_MESSAGE);
                    fixture.addView(preview[0], new LinearLayout.LayoutParams(
                            px[0], ViewGroup.LayoutParams.WRAP_CONTENT));
                });

                for (String layout : layouts) {
                    assertTrue(prefs.edit().putString("smart-history-layout", layout)
                            .putString("smart-text-overflow-mode", "auto_scroll").commit());
                    scenario.onActivity(activity -> {
                        title[0].refreshConfiguredBehavior();
                        preview[0].refreshConfiguredBehavior();
                        title[0].setText(LONG_MESSAGE);
                        preview[0].setText(LONG_MESSAGE);
                        assertEquals("Selection must be effective for " + layout,
                                "auto_scroll",
                                fr.neamar.kiss.ui.TextOverflowMode.effectiveMode(activity));
                    });

                    SystemClock.sleep(3200L);
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    scenario.onActivity(activity -> {
                        assertTrue("Title stayed stationary in " + layout,
                                title[0].getCurrentMarqueeOffset() > 0);
                        assertTrue("Two-line message did not advance in " + layout,
                                preview[0].getScrollY() > 0);
                    });

                    assertTrue(prefs.edit().putString("smart-text-overflow-mode", "auto_expand").commit());
                    scenario.onActivity(activity -> {
                        title[0].refreshConfiguredBehavior();
                        preview[0].refreshConfiguredBehavior();
                        assertEquals("auto_expand",
                                fr.neamar.kiss.ui.TextOverflowMode.effectiveMode(activity));
                        assertEquals("Expanded title must be reset", 0,
                                title[0].getCurrentMarqueeOffset());
                        assertEquals("Expanded preview must reset", 0, preview[0].getScrollY());
                    });
                }
            }
        } finally {
            prefs.edit().putString("smart-history-layout", oldLayout)
                    .putString("smart-text-overflow-mode", oldMode).commit();
        }
    }

    @Test public void nativeHistoryMetadataWrapsAndDateRailFadesAfterIdle() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(prefs.edit()
                .putString("smart-history-layout", "vertical")
                .putString("smart-text-overflow-mode", "auto_expand")
                .putInt("smart-history-meta-size-sp", 16).commit());

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            final View[] rail = new View[1];
            scenario.onActivity(activity -> {
                List<Result<?>> fixtures = BaselineBehaviorTest.fixtures(activity, false);
                RecordAdapter adapter = new RecordAdapter(activity, fixtures);
                View row = adapter.getView(1, null, activity.list);
                int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
                measure(row, screenWidth);
                TextView timestamp = row.findViewById(R.id.item_history_meta);
                assertNotNull(timestamp);
                assertTrue("Metadata must support the same long-text mode as other labels",
                        timestamp instanceof AutoMarqueeTextView);
                assertEquals("Expanded metadata cannot ellipsize", null, timestamp.getEllipsize());
                assertTrue("Long timestamp and usage metadata must wrap",
                        timestamp.getLineCount() > 1);
                assertTrue("Row width cannot extend beyond screen",
                        row.getMeasuredWidth() <= screenWidth);

                FrameLayout host = new FrameLayout(activity);
                host.addView(new View(activity), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                FrameLayout.LayoutParams hostLp = new FrameLayout.LayoutParams(
                        screenWidth, Math.round(280 * activity.getResources().getDisplayMetrics().density));
                ((ViewGroup) activity.findViewById(android.R.id.content)).addView(host, hostLp);
                ChronologicalDateScroller scroller = new ChronologicalDateScroller(activity, host);
                scroller.setSource(40, pos -> System.currentTimeMillis() - pos * 86_400_000L,
                        (pos, offset) -> { });
                scroller.onScroll(4, 5, 40);
                rail[0] = host.getChildAt(host.getChildCount() - 1);
                assertEquals(View.VISIBLE, rail[0].getVisibility());
            });

            SystemClock.sleep(1900L);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertTrue(
                    "Idle date-navigation scrollbar should fade away", rail[0].getAlpha() < 0.1f));
        }
    }

    private static void measure(View view, int width) {
        // Framework marquee layout requires LayoutParams, just as every actual
        // inflated History TextView has. The bare unit fixture otherwise crashes
        // inside Android TextView.makeNewLayout() after switching modes.
        if (view.getLayoutParams() == null) {
            view.setLayoutParams(new FrameLayout.LayoutParams(width,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
    }
}
