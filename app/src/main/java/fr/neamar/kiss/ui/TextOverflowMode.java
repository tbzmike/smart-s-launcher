package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

/** Shared long-text policy for Smart S Vertical list and Vertical cards result renderers. */
public final class TextOverflowMode {
    public static final String PREF_KEY = "smart-text-overflow-mode";
    public static final String AUTO_SCROLL = "auto_scroll";
    public static final String AUTO_EXPAND = "auto_expand";
    private static final String HISTORY_LAYOUT_KEY = "smart-history-layout";

    private TextOverflowMode() { }

    @NonNull
    public static String effectiveMode(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String mode = readString(prefs, PREF_KEY, AUTO_SCROLL);
        String layout = readString(prefs, HISTORY_LAYOUT_KEY, "vertical");
        return shouldExpand(mode, layout) ? AUTO_EXPAND : AUTO_SCROLL;
    }

    public static boolean isAutoExpandForHistory(@NonNull Context context) {
        return AUTO_EXPAND.equals(effectiveMode(context));
    }

    static boolean shouldExpand(String mode, String layout) {
        return AUTO_EXPAND.equals(normalizeMode(mode)) && isSupportedLayout(layout);
    }

    static String normalizeMode(String mode) {
        return AUTO_EXPAND.equals(mode) ? AUTO_EXPAND : AUTO_SCROLL;
    }

    static boolean isSupportedLayout(String layout) {
        return "vertical".equals(layout) || "vertical_cards".equals(layout);
    }

    /**
     * Auto-expand now starts collapsed instead of measuring the complete message into History.
     * Use the wrapped line count, not string length, so words/emoji/Unicode are never cut in half.
     */
    static int collapsedPreviewLineCount(int totalLines) {
        if (totalLines <= 0) return 0;
        if (totalLines == 1) return 1;
        return Math.max(1, (totalLines + 1) / 2);
    }

    private static String readString(SharedPreferences prefs, String key, String fallback) {
        try {
            String value = prefs.getString(key, fallback);
            return value == null ? fallback : value;
        } catch (ClassCastException ignored) {
            return fallback;
        }
    }
}
