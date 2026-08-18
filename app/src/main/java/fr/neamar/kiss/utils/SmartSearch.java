package fr.neamar.kiss.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight on-device search helpers for aliases and multi-word initialisms.
 * No network access, model loading, or background indexing is required.
 */
public final class SmartSearch {
    public static final String PREF_KEY_ALIASES = "smart-search-aliases";

    private static final Map<String, String> BUILT_IN_ALIASES;

    static {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("hotspot", "tethering");
        aliases.put("wifi", "connectivity");
        aliases.put("wireless", "connectivity");
        aliases.put("volume", "sound");
        aliases.put("screen", "display");
        aliases.put("power", "battery");
        aliases.put("battery saver", "battery");
        aliases.put("apps", "applications");
        aliases.put("app manager", "applications");
        aliases.put("bible", "jw library");
        aliases.put("gospel", "jw library");
        aliases.put("watchtower", "jw library");
        BUILT_IN_ALIASES = Collections.unmodifiableMap(aliases);
    }

    private SmartSearch() {
    }

    /**
     * Return the original query followed by alias-expanded fallbacks.
     * User aliases can be stored as strings in the form "alias=target".
     */
    @NonNull
    public static List<String> expandQueries(@NonNull Context context, @NonNull String query) {
        String trimmed = query.trim();
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        expanded.add(trimmed);

        String lower = trimmed.toLowerCase(Locale.ROOT);
        applyAliases(BUILT_IN_ALIASES, trimmed, lower, expanded);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> customAliases = prefs.getStringSet(PREF_KEY_ALIASES, Collections.emptySet());
        for (String entry : customAliases) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator >= entry.length() - 1) {
                continue;
            }
            String alias = entry.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String target = entry.substring(separator + 1).trim();
            if (!alias.isEmpty() && !target.isEmpty()) {
                applyAlias(alias, target, trimmed, lower, expanded);
            }
        }

        return new ArrayList<>(expanded);
    }

    private static void applyAliases(Map<String, String> aliases, String original, String lower,
                                     LinkedHashSet<String> expanded) {
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            applyAlias(alias.getKey(), alias.getValue(), original, lower, expanded);
        }
    }

    private static void applyAlias(String alias, String target, String original, String lower,
                                   LinkedHashSet<String> expanded) {
        if (lower.equals(alias)) {
            expanded.add(target);
            return;
        }

        String prefix = alias + " ";
        if (lower.startsWith(prefix)) {
            expanded.add(target + original.substring(alias.length()));
        }
    }

    /**
     * Strongly rank exact or prefix initialisms such as "LMW" for
     * "Life and Ministry Workbook". One-letter queries are deliberately ignored.
     */
    public static int initialismScore(@NonNull String query, @NonNull String name) {
        String compactQuery = query.replaceAll("[^\\p{L}\\p{N}]", "")
                .toLowerCase(Locale.ROOT);
        if (compactQuery.length() < 2) {
            return 0;
        }

        String[] words = name.trim().split("[^\\p{L}\\p{N}]+");
        StringBuilder initialism = new StringBuilder(words.length);
        for (String word : words) {
            if (!word.isEmpty()) {
                int codePoint = word.codePointAt(0);
                initialism.appendCodePoint(Character.toLowerCase(codePoint));
            }
        }

        String initials = initialism.toString();
        if (initials.equals(compactQuery)) {
            return 300 + compactQuery.length() * 10;
        }
        if (initials.startsWith(compactQuery)) {
            return 250 + compactQuery.length() * 10;
        }
        return 0;
    }
}
