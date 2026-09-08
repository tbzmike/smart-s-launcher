package fr.neamar.kiss.forwarder;

/** Shared bounded width rules for every history renderer. */
public final class HistoryEdgeWidthPolicy {
    private static final int NORMAL_PERCENT = 100;
    private static final int MAX_PERCENT = 200;

    private HistoryEdgeWidthPolicy() { }

    /**
     * Below 100% the normal width shrinks. From 100% to 200%, width grows only as far as the
     * physical viewport. 200% therefore means edge-to-edge, never a child wider than its parent.
     */
    public static int targetWidth(int normalWidthPx, int viewportWidthPx, int widthPercent) {
        if (normalWidthPx <= 0 || viewportWidthPx <= 0) return 0;
        int percent = Math.max(1, Math.min(MAX_PERCENT, widthPercent));
        int normal = Math.min(normalWidthPx, viewportWidthPx);
        if (percent <= NORMAL_PERCENT) {
            return Math.max(1, Math.round(normal * percent / 100f));
        }
        float progress = (percent - NORMAL_PERCENT) / 100f;
        return Math.min(viewportWidthPx, Math.max(1,
                Math.round(normal + (viewportWidthPx - normal) * progress)));
    }

    /** Release an existing side inset linearly; at 200% no horizontal gutter remains. */
    public static int insetForPercent(int baseInsetPx, int widthPercent) {
        if (baseInsetPx <= 0) return 0;
        int percent = Math.max(1, Math.min(MAX_PERCENT, widthPercent));
        if (percent <= NORMAL_PERCENT) return baseInsetPx;
        float remaining = (MAX_PERCENT - percent) / 100f;
        return Math.max(0, Math.round(baseInsetPx * remaining));
    }

    /** Move an existing normalized left bound toward the physical left edge. */
    public static float leftBoundForPercent(float baseLeft, int widthPercent) {
        float base = clamp01(baseLeft);
        int percent = Math.max(1, Math.min(MAX_PERCENT, widthPercent));
        if (percent <= NORMAL_PERCENT) return base;
        float remaining = (MAX_PERCENT - percent) / 100f;
        return base * remaining;
    }

    /** Move an existing normalized right bound toward the physical right edge. */
    public static float rightBoundForPercent(float baseRight, int widthPercent) {
        float base = clamp01(baseRight);
        int percent = Math.max(1, Math.min(MAX_PERCENT, widthPercent));
        if (percent <= NORMAL_PERCENT) return base;
        float progress = (percent - NORMAL_PERCENT) / 100f;
        return base + (1f - base) * progress;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
