package fr.neamar.kiss.forwarder;

/** Arithmetic shared by each independently rendered card icon and its preference slider. */
final class HistoryIconSizing {
    private HistoryIconSizing() {}

    static int combinedPercent(int categoryPercent, int cardPercent) {
        int category = Math.max(50, Math.min(240, categoryPercent));
        int card = Math.max(60, Math.min(180, cardPercent));
        return Math.max(50, Math.min(240, Math.round(category * card / 100f)));
    }

    static int iconDp(int baseDp, int effectivePercent) {
        return Math.max(32, Math.round(baseDp * Math.max(50,
                Math.min(240, effectivePercent)) / 100f));
    }
}
