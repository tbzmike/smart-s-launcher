package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import fr.neamar.kiss.UIColors;

/** Shared renderer for Smart S configurable result and history-metadata text. */
public final class SmartTextAppearance {
    public static final String PREF_TEXT_COLOR_INVERTER = "smart-text-color-inverter";
    private static final String DEFAULT_FAMILY = "sans";
    private static final String DEFAULT_STYLE = "normal";

    private SmartTextAppearance() {
    }

    public static void applySearchTitle(TextView view) {
        applyDefault(view, true);
    }

    public static void applySearchBody(TextView view) {
        applyDefault(view, false);
    }

    public static void applyHistoryMetadata(TextView view) {
        SharedPreferences prefs = prefs(view.getContext());
        int size = readInt(prefs, "smart-history-meta-size-sp", 12, 8, 28);
        String family = prefs.getString("smart-history-meta-font-family", DEFAULT_FAMILY);
        String style = prefs.getString("smart-history-meta-font-style", DEFAULT_STYLE);
        String colorValue = prefs.getString("smart-history-meta-color",
                UIColors.colorToString(UIColors.COLOR_SYSTEM));
        int themeColor = view.getCurrentTextColor();
        int selectedColor = applyTextColorInverter(view.getContext(),
                resolveConfiguredColor(colorValue, themeColor));

        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTypeface(typefaceFor(family, style));
        view.setTextColor(selectedColor);
        view.setAlpha(1f);
        if (!prefs.getBoolean("smart-history-meta-shadow", false)) view.setShadowLayer(0f, 0f, 0f, 0);
    }

    public static int historyMetadataSizeSp(Context context) {
        return readInt(prefs(context), "smart-history-meta-size-sp", 12, 8, 28);
    }

    public static int historyMetadataColor(Context context, int themeColor) {
        SharedPreferences prefs = prefs(context);
        String value = prefs.getString("smart-history-meta-color",
                UIColors.colorToString(UIColors.COLOR_SYSTEM));
        return applyTextColorInverter(context, resolveConfiguredColor(value, themeColor));
    }

    private static void applyDefault(TextView view, boolean title) {
        SharedPreferences prefs = prefs(view.getContext());
        int size = readInt(prefs,
                title ? "smart-default-text-primary-size-sp" : "smart-default-text-secondary-size-sp",
                title ? 18 : 14,
                title ? 10 : 8,
                title ? 40 : 32);
        String family = prefs.getString("smart-default-text-font-family", DEFAULT_FAMILY);
        String style = prefs.getString("smart-default-text-font-style", DEFAULT_STYLE);
        String colorValue = prefs.getString("smart-default-text-color",
                UIColors.colorToString(UIColors.COLOR_SYSTEM));
        int themeColor = view.getCurrentTextColor();
        int selectedColor = applyTextColorInverter(view.getContext(),
                resolveConfiguredColor(colorValue, themeColor));

        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTypeface(typefaceFor(family, style));
        view.setTextColor(selectedColor);
        view.setAlpha(1f);
        if (!prefs.getBoolean("smart-default-text-shadow", false)) view.setShadowLayer(0f, 0f, 0f, 0);
    }

    /**
     * Render-time only inversion. Stored color preferences are never modified, so disabling the
     * switch immediately restores the user's configured colors. Dark colors become white and
     * light colors become black while preserving the configured alpha channel.
     */
    public static int applyTextColorInverter(Context context, int color) {
        if (!prefs(context).getBoolean(PREF_TEXT_COLOR_INVERTER, false)) return color;
        int alpha = Color.alpha(color);
        double r = linear(Color.red(color) / 255.0);
        double g = linear(Color.green(color) / 255.0);
        double b = linear(Color.blue(color) / 255.0);
        double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        int target = luminance <= 0.179 ? 255 : 0;
        return Color.argb(alpha, target, target, target);
    }

    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static int readInt(SharedPreferences prefs, String key, int fallback, int min, int max) {
        int value = fallback;
        try {
            value = prefs.getInt(key, fallback);
        } catch (ClassCastException e) {
            try {
                value = Math.round(Float.parseFloat(prefs.getString(key, Integer.toString(fallback))));
            } catch (NumberFormatException | ClassCastException ignored) {
                value = fallback;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private static int resolveConfiguredColor(String value, int themeColor) {
        if (TextUtils.isEmpty(value)) return themeColor;
        try {
            int selected = Color.parseColor(value);
            return selected == UIColors.COLOR_SYSTEM ? themeColor : selected;
        } catch (IllegalArgumentException ignored) {
            return themeColor;
        }
    }

    private static String normalizeFamily(String value) {
        if (value == null) return "sans-serif";
        switch (value) {
            case "condensed": return "sans-serif-condensed";
            case "serif": return "serif";
            case "monospace": return "monospace";
            case "sans":
            default: return "sans-serif";
        }
    }

    public static Typeface typefaceFor(String family, String styleValue) {
        int style = Typeface.NORMAL;
        if (styleValue != null) {
            switch (styleValue) {
                case "bold": style = Typeface.BOLD; break;
                case "italic": style = Typeface.ITALIC; break;
                case "bold_italic": style = Typeface.BOLD_ITALIC; break;
                default: break;
            }
        }
        return Typeface.create(normalizeFamily(family), style);
    }

    /** Backward-compatible parser for existing per-view typography preferences. */
    public static Typeface typefaceFor(String value) {
        if (value == null) value = "sans_normal";
        String family = "sans";
        if (value.startsWith("condensed_")) family = "condensed";
        else if (value.startsWith("serif_")) family = "serif";
        else if (value.startsWith("monospace_")) family = "monospace";

        String style = "normal";
        if (value.endsWith("_bold_italic")) style = "bold_italic";
        else if (value.endsWith("_bold")) style = "bold";
        else if (value.endsWith("_italic")) style = "italic";
        return typefaceFor(family, style);
    }
}
