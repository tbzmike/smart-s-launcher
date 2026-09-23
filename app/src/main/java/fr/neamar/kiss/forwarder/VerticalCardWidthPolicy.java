package fr.neamar.kiss.forwarder;

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
