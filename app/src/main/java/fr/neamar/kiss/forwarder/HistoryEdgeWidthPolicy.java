package fr.neamar.kiss.forwarder;

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
