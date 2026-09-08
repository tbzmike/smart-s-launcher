from pathlib import Path

BASE = "3ae7421da1830b71a8b985f0d96da6c889d1270a"


def replace_exact(path, old, new, count=1):
    p = Path(path)
    text = p.read_text()
    actual = text.count(old)
    if actual != count:
        raise SystemExit(
            f"{path}: expected {count} occurrence(s), found {actual}: {old[:120]!r}"
        )
    p.write_text(text.replace(old, new, count))


Path("app/src/main/java/fr/neamar/kiss/forwarder/HistoryEdgeWidthPolicy.java").write_text(
    '''package fr.neamar.kiss.forwarder;

/**
 * Shared width rules for every history renderer.
 * 100% preserves the renderer's normal width, 200% reaches the physical viewport edges, and
 * 400% intentionally spans two viewport widths so rendered content can continue beyond both
 * screen edges on high-resolution displays.
 */
public final class HistoryEdgeWidthPolicy {
    private static final int NORMAL_PERCENT = 100;
    private static final int EDGE_PERCENT = 200;
    private static final int MAX_PERCENT = 400;

    private HistoryEdgeWidthPolicy() { }

    public static int targetWidth(int normalWidthPx, int viewportWidthPx, int widthPercent) {
        if (normalWidthPx <= 0 || viewportWidthPx <= 0) return 0;
        int percent = clampPercent(widthPercent);
        int normal = Math.min(normalWidthPx, viewportWidthPx);
        if (percent <= NORMAL_PERCENT) {
            return Math.max(1, Math.round(normal * percent / 100f));
        }
        if (percent <= EDGE_PERCENT) {
            float progress = (percent - NORMAL_PERCENT)
                    / (float) (EDGE_PERCENT - NORMAL_PERCENT);
            return Math.max(1, Math.round(normal
                    + (viewportWidthPx - normal) * progress));
        }
        float overscan = (percent - EDGE_PERCENT)
                / (float) (MAX_PERCENT - EDGE_PERCENT);
        return Math.max(1, Math.round(viewportWidthPx * (1f + overscan)));
    }

    /** Release an existing side inset by 200%; overscan beyond 200% keeps it at zero. */
    public static int insetForPercent(int baseInsetPx, int widthPercent) {
        if (baseInsetPx <= 0) return 0;
        int percent = clampPercent(widthPercent);
        if (percent <= NORMAL_PERCENT) return baseInsetPx;
        if (percent >= EDGE_PERCENT) return 0;
        float remaining = (EDGE_PERCENT - percent)
                / (float) (EDGE_PERCENT - NORMAL_PERCENT);
        return Math.max(0, Math.round(baseInsetPx * remaining));
    }

    /** 200% reaches x=0; 400% extends half a viewport beyond the physical left edge. */
    public static float leftBoundForPercent(float baseLeft, int widthPercent) {
        float base = clamp01(baseLeft);
        int percent = clampPercent(widthPercent);
        if (percent <= NORMAL_PERCENT) return base;
        if (percent <= EDGE_PERCENT) {
            float remaining = (EDGE_PERCENT - percent)
                    / (float) (EDGE_PERCENT - NORMAL_PERCENT);
            return base * remaining;
        }
        float overscan = (percent - EDGE_PERCENT)
                / (float) (MAX_PERCENT - EDGE_PERCENT);
        return -0.5f * overscan;
    }

    /** 200% reaches x=1; 400% extends half a viewport beyond the physical right edge. */
    public static float rightBoundForPercent(float baseRight, int widthPercent) {
        float base = clamp01(baseRight);
        int percent = clampPercent(widthPercent);
        if (percent <= NORMAL_PERCENT) return base;
        if (percent <= EDGE_PERCENT) {
            float progress = (percent - NORMAL_PERCENT)
                    / (float) (EDGE_PERCENT - NORMAL_PERCENT);
            return base + (1f - base) * progress;
        }
        float overscan = (percent - EDGE_PERCENT)
                / (float) (MAX_PERCENT - EDGE_PERCENT);
        return 1f + 0.5f * overscan;
    }

    /** Scale of a full viewport after the physical edges have already been reached. */
    public static float viewportScaleForPercent(int widthPercent) {
        int percent = clampPercent(widthPercent);
        if (percent <= EDGE_PERCENT) return 1f;
        return 1f + (percent - EDGE_PERCENT)
                / (float) (MAX_PERCENT - EDGE_PERCENT);
    }

    private static int clampPercent(int value) {
        return Math.max(1, Math.min(MAX_PERCENT, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
'''
)

Path("app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardWidthPolicy.java").write_text(
    '''package fr.neamar.kiss.forwarder;

/** Pure sizing rules for the Vertical Cards width editor. */
final class VerticalCardWidthPolicy {
    private static final int NORMAL_PERCENT = 100;
    private static final int EDGE_PERCENT = 200;
    private static final int MAX_PERCENT = 400;

    private VerticalCardWidthPolicy() { }

    static int targetWidth(int availablePx, int widthPercent) {
        if (availablePx <= 0) return 0;
        int percent = Math.max(1, Math.min(MAX_PERCENT, widthPercent));
        if (percent <= NORMAL_PERCENT) {
            return Math.max(1, Math.round(availablePx * percent / 100f));
        }
        if (percent <= EDGE_PERCENT) return availablePx;
        return Math.max(1, Math.round(availablePx
                * HistoryEdgeWidthPolicy.viewportScaleForPercent(percent)));
    }

    /** 100-200% releases old gutters; 200-400% keeps zero gutters while overscanning. */
    static int insetForPercent(int baseInsetPx, int widthPercent) {
        return HistoryEdgeWidthPolicy.insetForPercent(baseInsetPx, widthPercent);
    }
}
'''
)

resize = "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardGroupResizeController.java"
replace_exact(
    resize,
    "    private static final int MAX_WIDTH = 200;\n",
    "    private static final int MAX_WIDTH = 400;\n",
)
replace_exact(
    resize,
    '''        // Above 100%, consume the renderer's own horizontal gutters instead of creating a child
        // wider than its viewport. At 200% every Vertical Card can use the complete result width
        // without being centered behind (and clipped by) a narrower ScrollView.
''',
    '''        // 100-200% progressively consumes the renderer's own horizontal gutters. 200% is the
        // exact physical viewport width. 201-400% intentionally creates a centered child wider
        // than the viewport; the screen clips only the off-screen portion.
''',
)
replace_exact(
    resize,
    '''            int desired = widthPercent >= 100
                    ? ViewGroup.LayoutParams.MATCH_PARENT : targetWidth;
''',
    '''            int desired;
            if (widthPercent < 100) desired = targetWidth;
            else if (widthPercent <= 200) desired = ViewGroup.LayoutParams.MATCH_PARENT;
            else desired = targetWidth;
''',
)
replace_exact(
    resize,
    '''        explanation.setText(verticalCards
                ? "Resize every Vertical Card together"
                : "Resize this history style across the full screen");
''',
    '''        explanation.setText(verticalCards
                ? "Resize every Vertical Card together · 200% = screen edges · 400% = beyond screen"
                : "Resize this history style · 200% = screen edges · 400% = beyond screen");
''',
)

history = "app/src/main/java/fr/neamar/kiss/forwarder/HistoryDisplayForwarder.java"
replace_exact(
    history,
    "        int narrowerWidth = HistoryEdgeWidthPolicy.targetWidth(available, available, widthPercent);\n",
    "        int targetRowWidth = HistoryEdgeWidthPolicy.targetWidth(available, available, widthPercent);\n",
)
replace_exact(
    history,
    '''            int desiredWidth = widthPercent >= 100
                    ? ViewGroup.LayoutParams.MATCH_PARENT : narrowerWidth;
''',
    '''            int desiredWidth;
            if (widthPercent < 100) desiredWidth = targetRowWidth;
            else if (widthPercent <= 200) desiredWidth = ViewGroup.LayoutParams.MATCH_PARENT;
            else desiredWidth = targetRowWidth;
''',
)
replace_exact(
    history,
    '''        int width = Math.min(screenWidth - dp(24), Math.max(dp(150), baseWidth * sizePercent / 100));
''',
    '''        int normalWidth = Math.min(screenWidth - dp(24),
                Math.max(dp(150), baseWidth * sizePercent / 100));
        int width = HistoryEdgeWidthPolicy.targetWidth(
                normalWidth, screenWidth, historyWidthPercent());
''',
)
replace_exact(
    history,
    "        return safePrefInt(PREF_HISTORY_WIDTH, 100, 48, 200);\n",
    "        return safePrefInt(PREF_HISTORY_WIDTH, 100, 48, 400);\n",
)

square = "app/src/main/java/fr/neamar/kiss/forwarder/SquareUEdgeBoundsController.java"
replace_exact(
    square,
    "            return Math.max(48, Math.min(200, prefs.getInt(PREF_HISTORY_WIDTH, 100)));\n",
    "            return Math.max(48, Math.min(400, prefs.getInt(PREF_HISTORY_WIDTH, 100)));\n",
)
replace_exact(
    square,
    '''                return Math.max(48, Math.min(200,
                        Math.round(Float.parseFloat(prefs.getString(PREF_HISTORY_WIDTH, "100")))));
''',
    '''                return Math.max(48, Math.min(400,
                        Math.round(Float.parseFloat(prefs.getString(PREF_HISTORY_WIDTH, "100")))));
''',
)

record = "app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java"
replace_exact(
    record,
    "    private final WeakHashMap<View, Integer> baseRowMinimumHeight = new WeakHashMap<>();\n",
    "    private final WeakHashMap<View, Integer> baseRowMinimumHeight = new WeakHashMap<>();\n"
    "    private final WeakHashMap<View, Integer> baseRowLayoutWidths = new WeakHashMap<>();\n",
)
replace_exact(
    record,
    '''                    applyVerticalHistoryPolish(view, context);
                    verticalStyleSignatures.put(view, signature);
                }
''',
    '''                    applyVerticalHistoryPolish(view, context);
                    verticalStyleSignatures.put(view, signature);
                }
                applyVerticalHistoryWidth(view, parent, context);
''',
)
replace_exact(
    record,
    '        int widthPercent = safePercent(prefs, "smart-list-card-width-percent", 100, 48, 200);\n',
    '        int widthPercent = safePercent(prefs, "smart-list-card-width-percent", 100, 48, 400);\n',
)
replace_exact(
    record,
    '        result = 31 * result + safePercent(prefs, "smart-list-card-width-percent", 100, 48, 200);\n',
    '        result = 31 * result + safePercent(prefs, "smart-list-card-width-percent", 100, 48, 400);\n',
)
replace_exact(
    record,
    "    private void restoreVerticalHistoryAppearance(View row) {\n",
    '''    private void applyVerticalHistoryWidth(View row, ViewGroup parent, Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int widthPercent = safePercent(prefs, "smart-list-card-width-percent", 100, 48, 400);
        int viewportWidth = parent == null ? 0 : parent.getWidth();
        if (viewportWidth <= 0) {
            viewportWidth = context.getResources().getDisplayMetrics().widthPixels;
        }
        if (viewportWidth <= 0) return;

        ViewGroup.LayoutParams raw = row.getLayoutParams();
        if (!baseRowLayoutWidths.containsKey(row)) {
            baseRowLayoutWidths.put(row, raw == null
                    ? ViewGroup.LayoutParams.MATCH_PARENT : raw.width);
        }

        int targetWidth = HistoryEdgeWidthPolicy.targetWidth(
                viewportWidth, viewportWidth, widthPercent);
        int height = raw == null ? ViewGroup.LayoutParams.WRAP_CONTENT : raw.height;
        AbsListView.LayoutParams lp;
        if (raw instanceof AbsListView.LayoutParams) {
            lp = (AbsListView.LayoutParams) raw;
        } else {
            lp = new AbsListView.LayoutParams(targetWidth, height);
        }
        if (lp.width != targetWidth || row.getLayoutParams() != lp) {
            lp.width = targetWidth;
            row.setLayoutParams(lp);
        }

        float translationX = (viewportWidth - targetWidth) * 0.5f;
        if (Math.abs(row.getTranslationX() - translationX) > 0.5f) {
            row.setTranslationX(translationX);
        }
    }

    private void restoreVerticalHistoryAppearance(View row) {
''',
)
replace_exact(
    record,
    '''        Integer minimumHeight = baseRowMinimumHeight.get(row);
        if (minimumHeight != null) row.setMinimumHeight(minimumHeight);
        restorePrimaryIconSize(row);
''',
    '''        Integer minimumHeight = baseRowMinimumHeight.get(row);
        if (minimumHeight != null) row.setMinimumHeight(minimumHeight);
        Integer layoutWidth = baseRowLayoutWidths.get(row);
        ViewGroup.LayoutParams rowParams = row.getLayoutParams();
        if (layoutWidth != null && rowParams != null && rowParams.width != layoutWidth) {
            rowParams.width = layoutWidth;
            row.setLayoutParams(rowParams);
        }
        if (row.getTranslationX() != 0f) row.setTranslationX(0f);
        restorePrimaryIconSize(row);
''',
)

Path("app/src/test/java/fr/neamar/kiss/forwarder/HistoryEdgeWidthPolicyTest.java").write_text(
    '''package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class HistoryEdgeWidthPolicyTest {
    @Test void oneHundredPreservesNormalWidthInsetsAndBounds() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 100), is(600));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 100), is(8));
        assertThat((double) HistoryEdgeWidthPolicy.leftBoundForPercent(0.10f, 100), closeTo(0.10, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.rightBoundForPercent(0.90f, 100), closeTo(0.90, 0.0001));
    }

    @Test void oneHundredFiftyMovesHalfwayToPhysicalEdges() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 150), is(800));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 150), is(4));
        assertThat((double) HistoryEdgeWidthPolicy.leftBoundForPercent(0.10f, 150), closeTo(0.05, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.rightBoundForPercent(0.90f, 150), closeTo(0.95, 0.0001));
    }

    @Test void twoHundredMeansExactViewportEdges() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 200), is(1000));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 200), is(0));
        assertThat((double) HistoryEdgeWidthPolicy.leftBoundForPercent(0.10f, 200), closeTo(0.0, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.rightBoundForPercent(0.90f, 200), closeTo(1.0, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.viewportScaleForPercent(200), closeTo(1.0, 0.0001));
    }

    @Test void threeHundredExtendsQuarterViewportPastEachEdge() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 300), is(1500));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 300), is(0));
        assertThat((double) HistoryEdgeWidthPolicy.leftBoundForPercent(0.10f, 300), closeTo(-0.25, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.rightBoundForPercent(0.90f, 300), closeTo(1.25, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.viewportScaleForPercent(300), closeTo(1.5, 0.0001));
    }

    @Test void fourHundredSpansTwoViewportWidths() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 400), is(2000));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 400), is(0));
        assertThat((double) HistoryEdgeWidthPolicy.leftBoundForPercent(0.10f, 400), closeTo(-0.50, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.rightBoundForPercent(0.90f, 400), closeTo(1.50, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.viewportScaleForPercent(400), closeTo(2.0, 0.0001));
    }

    @Test void valuesAboveMaximumClampAtFourHundred() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 500), is(2000));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 500), is(0));
        assertThat((double) HistoryEdgeWidthPolicy.leftBoundForPercent(0.10f, 500), closeTo(-0.50, 0.0001));
        assertThat((double) HistoryEdgeWidthPolicy.rightBoundForPercent(0.90f, 500), closeTo(1.50, 0.0001));
    }

    @Test void valuesBelowOneHundredShrinkFromNormalWidth() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(600, 1000, 50), is(300));
        assertThat(HistoryEdgeWidthPolicy.insetForPercent(8, 50), is(8));
    }

    @Test void normalWidthLargerThanViewportStillUsesViewportAsEdgeReference() {
        assertThat(HistoryEdgeWidthPolicy.targetWidth(1200, 1000, 100), is(1000));
        assertThat(HistoryEdgeWidthPolicy.targetWidth(1200, 1000, 200), is(1000));
        assertThat(HistoryEdgeWidthPolicy.targetWidth(1200, 1000, 400), is(2000));
    }
}
'''
)

Path("app/src/test/java/fr/neamar/kiss/forwarder/VerticalCardWidthPolicyTest.java").write_text(
    '''package fr.neamar.kiss.forwarder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

public class VerticalCardWidthPolicyTest {
    @Test
    public void belowOneHundredShrinksCardButKeepsBaseInsets() {
        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 48), is(480));
        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 48), is(8));
        assertThat(VerticalCardWidthPolicy.insetForPercent(4, 48), is(4));
    }

    @Test
    public void oneHundredUsesViewportWithoutChangingNormalInsets() {
        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 100), is(1000));
        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 100), is(8));
        assertThat(VerticalCardWidthPolicy.insetForPercent(4, 100), is(4));
    }

    @Test
    public void oneHundredFiftyUsesFullViewportAndHalfInsets() {
        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 150), is(1000));
        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 150), is(4));
        assertThat(VerticalCardWidthPolicy.insetForPercent(4, 150), is(2));
    }

    @Test
    public void twoHundredUsesFullViewportWithNoInternalSideGutter() {
        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 200), is(1000));
        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 200), is(0));
        assertThat(VerticalCardWidthPolicy.insetForPercent(4, 200), is(0));
    }

    @Test
    public void threeHundredCreatesOneAndHalfViewportWidths() {
        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 300), is(1500));
        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 300), is(0));
    }

    @Test
    public void fourHundredCreatesTwoViewportWidths() {
        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 400), is(2000));
        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 400), is(0));
    }

    @Test
    public void valuesAboveMaximumClampAtFourHundred() {
        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 500), is(2000));
        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 500), is(0));
    }
}
'''
)

replace_exact(
    "app/build.gradle",
    "// Smart S Launcher 3.30.69 - shared edge-to-edge width across every history style\n",
    "// Smart S Launcher 3.30.69 - global 48-400% history width, 200% edge-to-edge\n",
)
