package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;

import androidx.preference.PreferenceManager;

import fr.neamar.kiss.R;
import fr.neamar.kiss.UIColors;

/**
 * Communication-row text that follows the Vertical List label/body appearance preferences in both
 * history and search results. History spacing/icon sizing stays owned by RecordAdapter; this class
 * only keeps communication typography/color consistent wherever the shared list row is rendered.
 */
public class VerticalListStyledTextView extends AutoMarqueeTextView {
    public VerticalListStyledTextView(Context context) {
        super(context);
    }

    public VerticalListStyledTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VerticalListStyledTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyConfiguredAppearance();
    }

    private void applyConfiguredAppearance() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        boolean label = getId() == R.id.item_communication_title;
        boolean body = getId() == R.id.item_communication_body;

        String sizeKey = label ? "smart-list-label-size-sp" : "smart-list-body-size-sp";
        String fontKey = label ? "smart-list-label-font" : "smart-list-body-font";
        String colorKey = label ? "smart-list-label-color" : "smart-list-body-color";
        String contrastKey = label ? "smart-list-label-contrast" : "smart-list-body-contrast";

        int size = readInt(prefs, sizeKey, label ? 18 : 14,
                label ? 10 : 8, label ? 40 : 32);
        int contrast = readInt(prefs, contrastKey, 100, 25, 200);
        String font = prefs.getString(fontKey, label ? "sans_bold" : "sans_normal");
        String colorValue = prefs.getString(colorKey, UIColors.colorToString(UIColors.COLOR_SYSTEM));

        int themeColor = getCurrentTextColor();
        int selectedColor = resolveConfiguredColor(colorValue, themeColor);

        setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        setTypeface(typefaceFor(font));
        setTextColor(applyContrast(selectedColor, themeColor, contrast));
        setAlpha(1f);

        if (body) {
            setSingleLine(false);
            setMaxLines(Integer.MAX_VALUE);
            setHorizontallyScrolling(false);
            setEllipsize(null);
            setHorizontalFadingEdgeEnabled(false);
            setSelected(false);
        }
    }

    private int readInt(SharedPreferences prefs, String key, int fallback, int min, int max) {
        Object raw = prefs.getAll().get(key);
        int value = fallback;
        if (raw instanceof Number) {
            value = Math.round(((Number) raw).floatValue());
        } else if (raw instanceof String) {
            try {
                value = Math.round(Float.parseFloat((String) raw));
            } catch (NumberFormatException ignored) {
                value = fallback;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private int resolveConfiguredColor(String value, int themeColor) {
        if (TextUtils.isEmpty(value)) return themeColor;
        try {
            int selected = Color.parseColor(value);
            return selected == UIColors.COLOR_SYSTEM ? themeColor : selected;
        } catch (IllegalArgumentException ignored) {
            return themeColor;
        }
    }

    private int applyContrast(int color, int themeReferenceColor, int contrast) {
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
                blend(red, target, amount),
                blend(green, target, amount),
                blend(blue, target, amount));
    }

    private int blend(int value, int target, float amount) {
        return Math.max(0, Math.min(255, Math.round(value + (target - value) * amount)));
    }

    private float relativeLuminance(int color) {
        return (0.2126f * Color.red(color)
                + 0.7152f * Color.green(color)
                + 0.0722f * Color.blue(color)) / 255f;
    }

    private Typeface typefaceFor(String value) {
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
