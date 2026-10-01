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

    /** Dedicated appearance for query/search result titles. */
    public static void applySearchTitle(TextView view) {
        applySearch(view, true);
    }

    /** Dedicated appearance for query/search result subtitles, tags and message previews. */
    public static void applySearchBody(TextView view) {
        applySearch(view, false);
    }

    /** Launcher-wide fallback primary typography (search input, generic titles, etc.). */
    public static void applyDefaultTitle(TextView view) {
        applyDefault(view, true);
    }

    /** Launcher-wide fallback secondary typography. */
    public static void applyDefaultBody(TextView view) {
        applyDefault(view, false);
    }

    public static void applyHistoryMetadata(TextView view) {
        SharedPreferences prefs = prefs(view.getContext());
        int size = readInt(prefs, "smart-history-meta-size-sp", 12, 8, 28);
        String font = prefs.getString("smart-history-meta-font", "sans_normal");
        String colorValue = prefs.getString("smart-history-meta-color",
                UIColors.colorToString(UIColors.COLOR_SYSTEM));
        int contrast = readInt(prefs, "smart-history-meta-contrast", 100, 25, 200);
        int themeColor = view.getCurrentTextColor();
        int selectedColor = resolveConfiguredColor(colorValue, themeColor);
        int renderedColor = applyTextColorInverter(view.getContext(),
                applyContrast(selectedColor, themeColor, contrast));

        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTypeface(typefaceFor(font));
        view.setTextColor(renderedColor);
        view.setAlpha(1f);
        if (!prefs.getBoolean("smart-history-meta-shadow", false)) {
            view.setShadowLayer(0f, 0f, 0f, 0);
        }
    }

    public static int historyMetadataSizeSp(Context context) {
        return readInt(prefs(context), "smart-history-meta-size-sp", 12, 8, 28);
    }

    public static int historyMetadataColor(Context context, int themeColor) {
        SharedPreferences prefs = prefs(context);
        String value = prefs.getString("smart-history-meta-color",
                UIColors.colorToString(UIColors.COLOR_SYSTEM));
        int contrast = readInt(prefs, "smart-history-meta-contrast", 100, 25, 200);
        int selected = resolveConfiguredColor(value, themeColor);
        return applyTextColorInverter(context, applyContrast(selected, themeColor, contrast));
    }

    private static void applySearch(TextView view, boolean title) {
        SharedPreferences prefs = prefs(view.getContext());
        int size = readInt(prefs,
                title ? "smart-search-title-size-sp" : "smart-search-body-size-sp",
                title ? 18 : 14,
                title ? 10 : 8,
                title ? 40 : 32);
        String font = prefs.getString(
                title ? "smart-search-title-font" : "smart-search-body-font",
                "sans_normal");
        String colorValue = prefs.getString(
                title ? "smart-search-title-color" : "smart-search-body-color",
                UIColors.colorToString(UIColors.COLOR_SYSTEM));
        int contrast = readInt(prefs,
                title ? "smart-search-title-contrast" : "smart-search-body-contrast",
                100, 25, 200);

        int themeColor = view.getCurrentTextColor();
        int selectedColor = resolveConfiguredColor(colorValue, themeColor);
        int renderedColor = applyTextColorInverter(view.getContext(),
                applyContrast(selectedColor, themeColor, contrast));

        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTypeface(typefaceFor(font));
        view.setTextColor(renderedColor);
        view.setAlpha(1f);
        applyDefaultShadow(view, prefs);
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
        applyDefaultShadow(view, prefs);
    }

    private static void applyDefaultShadow(TextView view, SharedPreferences prefs) {
        if (!prefs.getBoolean("smart-default-text-shadow", false)) {
            view.setShadowLayer(0f, 0f, 0f, 0);
            return;
        }

        // Make the ON state deterministic instead of merely preserving whatever a theme happened
        // to provide. Use the opposite luminance so the shadow remains useful on light/dark text.
        int color = view.getCurrentTextColor();
        double luminance = 0.2126 * linear(Color.red(color) / 255.0)
                + 0.7152 * linear(Color.green(color) / 255.0)
                + 0.0722 * linear(Color.blue(color) / 255.0);
        int shadow = luminance >= 0.5 ? Color.argb(180, 0, 0, 0)
                : Color.argb(180, 255, 255, 255);
        view.setShadowLayer(2f, 1f, 1f, shadow);
    }

    static int applyContrast(int color, int themeReferenceColor, int contrast) {
        int alpha = Color.alpha(color);
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);

        if (contrast < 100) {
            float strength = 0.25f + (contrast / 100f) * 0.75f;
            alpha = Math.max(0, Math.min(255, Math.round(alpha * strength)));
            return Color.argb(alpha, red, green, blue);
        }
        if (contrast == 100) return color;

        boolean themeUsesLightText = relativeLuminance(themeReferenceColor) >= 0.5f;
        int target = themeUsesLightText ? 255 : 0;
        float amount = Math.min(1f, (contrast - 100) / 100f);
        return Color.argb(alpha,
                blendChannel(red, target, amount),
                blendChannel(green, target, amount),
                blendChannel(blue, target, amount));
    }

    private static int blendChannel(int value, int target, float amount) {
        return Math.max(0, Math.min(255, Math.round(value + (target - value) * amount)));
    }

    private static float relativeLuminance(int color) {
        return (0.2126f * Color.red(color)
                + 0.7152f * Color.green(color)
                + 0.0722f * Color.blue(color)) / 255f;
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
