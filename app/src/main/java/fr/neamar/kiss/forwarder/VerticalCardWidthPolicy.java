package fr.neamar.kiss.forwarder;

/** Pure sizing rules for the Vertical Cards width editor. */
final class VerticalCardWidthPolicy {
    private static final int NORMAL_PERCENT = 100;
    private static final int MAX_PERCENT = 200;

    private VerticalCardWidthPolicy() { }

    static int targetWidth(int availablePx, int widthPercent) {
        if (availablePx <= 0) return 0;
        int percent = Math.max(1, Math.min(MAX_PERCENT, widthPercent));
        if (percent >= NORMAL_PERCENT) return availablePx;
        return Math.max(1, Math.round(availablePx * percent / 100f));
    }

    /**
     * Width above 100% progressively releases an existing horizontal inset. 200% means the inset
     * is fully consumed; it never means creating a view twice as wide as the physical viewport.
     */
    static int insetForPercent(int baseInsetPx, int widthPercent) {
        if (baseInsetPx <= 0) return 0;
        int percent = Math.max(1, Math.min(MAX_PERCENT, widthPercent));
        if (percent <= NORMAL_PERCENT) return baseInsetPx;
        float remaining = (MAX_PERCENT - percent) / 100f;
        return Math.max(0, Math.round(baseInsetPx * remaining));
    }
}
