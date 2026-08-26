package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import fr.neamar.kiss.UIColors;

/** Shared renderer for Smart S configurable result and history-metadata text. */
public final class SmartTextAppearance {
    private SmartTextAppearance() {
    }

    public static void applySearchTitle(TextView view) {
        apply(view, "smart-search-title", 18, 10, 40, "sans_normal");
    }

    public static void applySearchBody(TextView view) {
        apply(view, "smart-search-body", 14, 8, 32, "sans_normal");
    }

    public static int historyMetadataSizeSp(Context context) {
        return readInt(prefs(context), "smart-history-meta-size-sp", 12, 8, 28);
    }

    public static int historyMetadataColor(Context context, int themeColor) {
        SharedPreferences prefs = prefs(context);
        String value = prefs.getString("smart-history-meta-color",
                UIColors.colorToString(UIColors.COLOR_SYSTEM));
        int selected = resolveConfiguredColor(value, themeColor);
        int contrast = readInt(prefs, "smart-history-meta-contrast", 100, 25, 200);
        return applyContrast(selected, themeColor, contrast);
    }

    public static Typeface historyMetadataTypeface(Context context) {
        return typefaceFor(prefs(context).getString("smart-history-meta-font", "sans_normal"));
    }

    public static RelativeSizeSpan relativeMetadataSize(Context context, TextView target) {
        float baseSp = target.getTextSize() / target.getResources().getDisplayMetrics().scaledDensity;
        if (baseSp <= 0f) baseSp = 14f;
        return new RelativeSizeSpan(historyMetadataSizeSp(context) / baseSp);
    }

    public static ForegroundColorSpan metadataColorSpan(Context context, TextView target) {
        return new ForegroundColorSpan(historyMetadataColor(context, target.getCurrentTextColor()));
    }

    public static StyleSpan metadataStyleSpan(Context context) {
        Typeface typeface = historyMetadataTypeface(context);
        return new StyleSpan(typeface == null ? Typeface.NORMAL : typeface.getStyle());
    }

    private static void apply(TextView view, String prefix, int fallbackSize, int minSize,
                              int maxSize, String fallbackFont) {
        SharedPreferences prefs = prefs(view.getContext());
        int size = readInt(prefs, prefix + "-size-sp", fallbackSize, minSize, maxSize);
        int contrast = readInt(prefs, prefix + "-contrast", 100, 25, 200);
        String font = prefs.getString(prefix + "-font", fallbackFont);
        String colorValue = prefs.getString(prefix + "-color",
                UIColors.colorToString(UIColors.COLOR_SYSTEM));
        int themeColor = view.getCurrentTextColor();
        int selectedColor = resolveConfiguredColor(colorValue, themeColor);

        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTypeface(typefaceFor(font));
        view.setTextColor(applyContrast(selectedColor, themeColor, contrast));
        view.setAlpha(1f);
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

    private static int applyContrast(int color, int themeReferenceColor, int contrast) {
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
        boolean lightText = relativeLuminance(themeReferenceColor) >= 0.5f;
        int target = lightText ? 255 : 0;
        float amount = Math.min(1f, (contrast - 100) / 100f);
        return Color.argb(alpha,
                blend(red, target, amount), blend(green, target, amount), blend(blue, target, amount));
    }

    private static int blend(int value, int target, float amount) {
        return Math.max(0, Math.min(255, Math.round(value + (target - value) * amount)));
    }

    private static float relativeLuminance(int color) {
        return (0.2126f * Color.red(color) + 0.7152f * Color.green(color)
                + 0.0722f * Color.blue(color)) / 255f;
    }

    public static Typeface typefaceFor(String value) {
        if (value == null) value = "sans_normal";
        String family = "sans-serif";
        int style = Typeface.NORMAL;
        switch (value) {
            case "sans_bold": style = Typeface.BOLD; break;
            case "sans_italic": style = Typeface.ITALIC; break;
            case "sans_bold_italic": style = Typeface.BOLD_ITALIC; break;
            case "condensed_normal": family = "sans-serif-condensed"; break;
            case "condensed_bold": family = "sans-serif-condensed"; style = Typeface.BOLD; break;
            case "serif_normal": family = "serif"; break;
            case "serif_bold": family = "serif"; style = Typeface.BOLD; break;
            case "monospace_normal": family = "monospace"; break;
            case "monospace_bold": family = "monospace"; style = Typeface.BOLD; break;
            case "sans_normal":
            default: break;
        }
        return Typeface.create(family, style);
    }
}
