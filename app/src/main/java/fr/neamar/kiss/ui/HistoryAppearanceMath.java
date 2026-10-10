package fr.neamar.kiss.ui;

/**
 * Single source of truth for independent History text and icon sizing.
 * The global text slider acts as a multiplier on explicit label/body/metadata sizes,
 * rather than getting overwritten by subsequent per-category formatting.
 */
public final class HistoryAppearanceMath {
    private HistoryAppearanceMath() {}

    public static float scaledTextSp(int categorySp, int globalPercent) {
        int global = Math.max(70, Math.min(160, globalPercent));
        return Math.max(1f, categorySp * (global / 100f));
    }

    public static int scaledIconPx(int basePx, int percent) {
        return Math.max(1, Math.round(basePx * (Math.max(50, Math.min(240, percent)) / 100f)));
    }
}
