package fr.neamar.kiss.utils.fuzzy;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import fr.neamar.kiss.normalizer.StringNormalizer;
import fr.neamar.kiss.utils.SmartSearch;

/** Shared smart matching policy for searchable POJOs. */
public final class SmartMatcher {
    private static final AtomicLong SEARCH_GENERATION = new AtomicLong();
    private static final ThreadLocal<PreparedQuery> PREPARED_QUERY = new ThreadLocal<>();

    private SmartMatcher() {
    }

    /**
     * Mark the beginning of a new search operation. This keeps per-query preparation reusable for
     * every candidate in one search while guaranteeing that changed search preferences are picked
     * up even when the user repeats exactly the same text later.
     */
    public static void beginSearch() {
        SEARCH_GENERATION.incrementAndGet();
    }

    public static MatchInfo match(@NonNull Context context, @NonNull String query,
                                  StringNormalizer.Result normalizedCandidate,
                                  @NonNull String displayName) {
        if (normalizedCandidate == null) {
            return MatchInfo.UNMATCHED;
        }

        long generation = SEARCH_GENERATION.get();
        PreparedQuery prepared = PREPARED_QUERY.get();
        if (prepared == null || prepared.generation != generation || !prepared.query.equals(query)) {
            prepared = new PreparedQuery(context, query, generation);
            PREPARED_QUERY.set(prepared);
        }
        return prepared.match(normalizedCandidate, displayName);
    }

    private static final class PreparedQuery {
        final long generation;
        final String query;
        final List<PreparedVariant> variants;
        final boolean typoToleranceEnabled;

        PreparedQuery(@NonNull Context context, @NonNull String query, long generation) {
            this.generation = generation;
            this.query = query;

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            this.typoToleranceEnabled = prefs.getBoolean("enable-typo-tolerance", true);

            List<String> expandedQueries = SmartSearch.expandQueries(context, query);
            this.variants = new ArrayList<>(expandedQueries.size());
            for (String expandedQuery : expandedQueries) {
                StringNormalizer.Result normalizedQuery =
                        StringNormalizer.normalizeWithResult(expandedQuery, false);
                if (normalizedQuery.codePoints.length == 0) continue;
                variants.add(new PreparedVariant(
                        expandedQuery,
                        FuzzyFactory.createFuzzyScore(context, normalizedQuery.codePoints)));
            }
        }

        MatchInfo match(@NonNull StringNormalizer.Result normalizedCandidate,
                        @NonNull String displayName) {
            MatchInfo best = MatchInfo.UNMATCHED;
            for (PreparedVariant variant : variants) {
                MatchInfo fuzzy = variant.scorer.match(normalizedCandidate.codePoints);
                if (fuzzy.match && (!best.match || fuzzy.score > best.score)) {
                    best = new MatchInfo(true, fuzzy.score);
                }

                if (typoToleranceEnabled) {
                    MatchInfo typo = TypoTolerance.matchPrepared(variant.query, displayName);
                    if (typo.match && (!best.match || typo.score > best.score)) {
                        best = typo;
                    }
                }
            }

            int initialism = SmartSearch.initialismScore(query, displayName);
            if (initialism > 0 && (!best.match || initialism > best.score)) {
                best = new MatchInfo(true, initialism);
            }
            return best;
        }
    }

    private static final class PreparedVariant {
        final String query;
        final FuzzyScore scorer;

        PreparedVariant(String query, FuzzyScore scorer) {
            this.query = query;
            this.scorer = scorer;
        }
    }
}
