package fr.neamar.kiss.searcher;

import java.util.Locale;

/** Pure rules for the bounded, persistent search-results recall window. */
public final class SearchRecallPolicy {
    private SearchRecallPolicy() { }

    public static String normalize(String query) {
        return query == null ? "" : query.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    public static int firstAllowedIndex(int total, int configuredHistoryResults) {
        return Math.max(0, total - Math.max(0, configuredHistoryResults));
    }

    public static boolean matches(String savedQuery, String enteredQuery) {
        return savedQuery != null && enteredQuery != null && !enteredQuery.isEmpty()
                && savedQuery.startsWith(enteredQuery);
    }
}
