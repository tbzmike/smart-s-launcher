package fr.neamar.kiss.utils.fuzzy;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.util.Locale;

/**
 * Adds typo tolerance on top of KISS' fast subsequence matcher.
 */
public final class TypoTolerance {
    private TypoTolerance() {
    }

    public static MatchInfo match(@NonNull Context context, @NonNull String query,
                                  @NonNull String candidate) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean("enable-typo-tolerance", true)) {
            return MatchInfo.UNMATCHED;
        }
        return matchPrepared(query, candidate);
    }

    /**
     * Execute typo matching after the caller has already verified the preference for the current
     * search. SmartMatcher uses this to avoid a SharedPreferences lookup for every candidate.
     */
    static MatchInfo matchPrepared(@NonNull String query, @NonNull String candidate) {
        String pattern = query.trim().toLowerCase(Locale.ROOT);
        String text = candidate.trim().toLowerCase(Locale.ROOT);
        if (pattern.length() < 3 || text.isEmpty()) {
            return MatchInfo.UNMATCHED;
        }

        int bestDistance = Integer.MAX_VALUE;
        int bestLength = Math.max(pattern.length(), text.length());

        // Whole-label comparison.
        bestDistance = Math.min(bestDistance, boundedDamerauLevenshtein(pattern, text, maxDistance(pattern.length())));

        // Also compare individual words so "batery" can match "Battery saver".
        for (String word : text.split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) {
                continue;
            }
            int distance = boundedDamerauLevenshtein(pattern, word, maxDistance(pattern.length()));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestLength = Math.max(pattern.length(), word.length());
            }
        }

        int allowed = maxDistance(pattern.length());
        if (bestDistance > allowed) {
            return MatchInfo.UNMATCHED;
        }

        // Keep typo matches below strong exact/prefix/initialism matches.
        int score = 90 - (bestDistance * 18) - Math.abs(bestLength - pattern.length()) * 2;
        return new MatchInfo(true, score);
    }

    private static int maxDistance(int length) {
        if (length <= 4) return 1;
        if (length <= 8) return 2;
        return 3;
    }

    /** Bounded Damerau-Levenshtein distance with adjacent transposition support. */
    static int boundedDamerauLevenshtein(String a, String b, int maxDistance) {
        if (Math.abs(a.length() - b.length()) > maxDistance) {
            return maxDistance + 1;
        }
        int[] previousPrevious = null;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int value = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
                if (previousPrevious != null && i > 1 && j > 1
                        && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    value = Math.min(value, previousPrevious[j - 2] + 1);
                }
                current[j] = value;
                rowMin = Math.min(rowMin, value);
            }
            if (rowMin > maxDistance) return maxDistance + 1;
            previousPrevious = previous;
            previous = current;
            current = new int[b.length() + 1];
        }
        return previous[b.length()];
    }
}
