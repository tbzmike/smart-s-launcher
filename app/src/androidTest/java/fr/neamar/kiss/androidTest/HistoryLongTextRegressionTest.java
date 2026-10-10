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
                measure(text, px);
                // TextView can retain the old multi-line Layout object even as
                // maxLines and measured compact height have already switched.
                assertEquals("Compact marquee must enforce one visible line",
                        1, text.getMaxLines());
                assertEquals(TextUtils.TruncateAt.MARQUEE, text.getEllipsize());
                assertTrue("Long text must not keep the old expanded tile height",
                        text.getMeasuredHeight() <= text.getLineHeight() * 2);

                measure(message, px);
                assertTrue("Compact preview must fit inside a two-line viewport",
                        message.getMeasuredHeight() <= message.getLineHeight() * 3);

                assertTrue(prefs.edit().putString("smart-text-overflow-mode", "auto_expand").commit());
                measure(text, px);
                assertTrue("Switching back must restore every wrapped line", text.getLineCount() > 2);
                measure(message, px);
                assertTrue("Switching back restores full preview height",
                        message.getMeasuredHeight() >= message.getLineHeight() * 3);
            });
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
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
    }
}
