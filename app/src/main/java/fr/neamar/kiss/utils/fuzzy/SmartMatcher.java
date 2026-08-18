package fr.neamar.kiss.utils.fuzzy;

import android.content.Context;

import androidx.annotation.NonNull;

import fr.neamar.kiss.normalizer.StringNormalizer;
import fr.neamar.kiss.utils.SmartSearch;

/** Shared smart matching policy for searchable POJOs. */
public final class SmartMatcher {
    private SmartMatcher() {
    }

    public static MatchInfo match(@NonNull Context context, @NonNull String query,
                                  StringNormalizer.Result normalizedCandidate,
                                  @NonNull String displayName) {
        if (normalizedCandidate == null) {
            return MatchInfo.UNMATCHED;
        }

        MatchInfo best = MatchInfo.UNMATCHED;
        for (String expandedQuery : SmartSearch.expandQueries(context, query)) {
            StringNormalizer.Result normalizedQuery = StringNormalizer.normalizeWithResult(expandedQuery, false);
            if (normalizedQuery.codePoints.length == 0) continue;

            FuzzyScore scorer = FuzzyFactory.createFuzzyScore(context, normalizedQuery.codePoints);
            MatchInfo fuzzy = scorer.match(normalizedCandidate.codePoints);
            if (fuzzy.match && (!best.match || fuzzy.score > best.score)) {
                best = new MatchInfo(true, fuzzy.score);
            }

            MatchInfo typo = TypoTolerance.match(context, expandedQuery, displayName);
            if (typo.match && (!best.match || typo.score > best.score)) {
                best = typo;
            }
        }

        int initialism = SmartSearch.initialismScore(query, displayName);
        if (initialism > 0 && (!best.match || initialism > best.score)) {
            best = new MatchInfo(true, initialism);
        }
        return best;
    }
}
