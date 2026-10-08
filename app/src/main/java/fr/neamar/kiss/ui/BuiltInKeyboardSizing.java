package fr.neamar.kiss.ui;

/**
 * Bounds and calculations for the launcher-owned built-in keyboard.
 *
 * <p>The height ceiling is deliberately fixed at 50% of the available launcher surface so the
 * keyboard can be enlarged without ever consuming the whole Home screen. Width, key surface size
 * and label size remain independent controls.</p>
 */
public final class BuiltInKeyboardSizing {
    public static final String PREF_HEIGHT_PERCENT = "built-in-keyboard-height-percent";
    public static final String PREF_WIDTH_PERCENT = "built-in-keyboard-width-percent";
    public static final String PREF_BUTTON_PERCENT = "built-in-keyboard-button-size-percent";
    public static final String PREF_LABEL_SIZE_SP = "built-in-keyboard-label-size-sp";

    public static final int MIN_HEIGHT_PERCENT = 20;
    public static final int MAX_HEIGHT_PERCENT = 50;
    public static final int DEFAULT_HEIGHT_PERCENT = 30;

    public static final int MIN_WIDTH_PERCENT = 45;
    public static final int MAX_WIDTH_PERCENT = 100;
    public static final int DEFAULT_WIDTH_PERCENT = 100;

    public static final int MIN_BUTTON_PERCENT = 55;
    public static final int MAX_BUTTON_PERCENT = 100;
    public static final int DEFAULT_BUTTON_PERCENT = 100;

    public static final int MIN_LABEL_SIZE_SP = 10;
    public static final int MAX_LABEL_SIZE_SP = 36;
    public static final int DEFAULT_LABEL_SIZE_SP = 18;

    private BuiltInKeyboardSizing() { }

    public static int clampHeightPercent(int value) {
        return clamp(value, MIN_HEIGHT_PERCENT, MAX_HEIGHT_PERCENT);
    }

    public static int clampWidthPercent(int value) {
        return clamp(value, MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT);
    }

    public static int clampButtonPercent(int value) {
        return clamp(value, MIN_BUTTON_PERCENT, MAX_BUTTON_PERCENT);
    }

    public static int clampLabelSizeSp(int value) {
        return clamp(value, MIN_LABEL_SIZE_SP, MAX_LABEL_SIZE_SP);
    }

    public static int heightPx(int availableHeightPx, int requestedPercent) {
        int available = Math.max(0, availableHeightPx);
        int percent = clampHeightPercent(requestedPercent);
        int requested = Math.round(available * (percent / 100f));
        int halfScreen = available / 2;
        return Math.min(requested, halfScreen);
    }

    public static int widthPx(int availableWidthPx, int requestedPercent) {
        int available = Math.max(0, availableWidthPx);
        int percent = clampWidthPercent(requestedPercent);
        return Math.round(available * (percent / 100f));
    }

    /**
     * Additional per-edge gap used to make button surfaces smaller without changing label size.
     * 100% preserves the existing key geometry; lower values progressively add breathing room.
     */
    public static int extraButtonInsetDp(int requestedPercent) {
        int percent = clampButtonPercent(requestedPercent);
        return Math.round((100 - percent) / 5f);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
