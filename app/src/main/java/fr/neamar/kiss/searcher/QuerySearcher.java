package fr.neamar.kiss.searcher;

import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ValuedHistoryRecord;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.PojoWithTags;

/**
 * AsyncTask retrieving data from the providers and updating the view.
 *
 * Semantic mode uses a hybrid reranker instead of treating embeddings as a weak fallback. Exact
 * lexical matches remain protected, while strong semantic matches can outrank weak fuzzy matches.
 */
public class QuerySearcher extends Searcher {
    public static final String PREF_SEMANTIC_RERANK = "semantic-rerank-enabled";
    public static final String PREF_SEMANTIC_WEIGHT = "semantic-rerank-weight";

    private static int MAX_RESULT_COUNT = -1;
    private HashMap<String, Integer> knownIds;
    private final SharedPreferences prefs;
    private final Set<String> lexicalIds = new HashSet<>();
    private boolean semanticPass;
    private boolean semanticEnabled;
    private boolean semanticRerank;
    private float semanticThreshold;
    private float semanticWeight;
    private int semanticDimensions;

    public QuerySearcher(MainActivity activity, String query, boolean isRefresh) {
        super(activity, query, isRefresh);
        prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    @Override
    protected int getMaxResultCount() {
        if (MAX_RESULT_COUNT == -1) {
            try {
                MAX_RESULT_COUNT = Double.valueOf(prefs.getString("number-of-display-elements",
                        String.valueOf(DEFAULT_MAX_RESULTS))).intValue();
            } catch (NumberFormatException e) {
                MAX_RESULT_COUNT = DEFAULT_MAX_RESULTS;
            }
        }
        return MAX_RESULT_COUNT;
    }

    @Override
    public boolean addResults(List<? extends Pojo> pojos) {
        if (semanticPass) {
            List<Pojo> semanticMatches = new ArrayList<>();
            for (Pojo pojo : pojos) {
                if (pojo == null || lexicalIds.contains(pojo.id)) continue;
                float score = SemanticEmbeddingScorer.score(query, pojo, semanticDimensions);
                if (score < semanticThreshold) continue;

                if (semanticRerank) {
                    pojo.relevance = hybridRelevance(pojo, pojo.relevance, score, false);
                } else {
                    pojo.relevance = Math.max(pojo.relevance, 120 + Math.round(score * 280f));
                    if (pojo.isDisabled()) pojo.relevance -= 200;
                }
                semanticMatches.add(pojo);
            }
            return super.addResults(semanticMatches);
        }

        for (Pojo pojo : pojos) {
            if (pojo == null) continue;
            lexicalIds.add(pojo.id);

            int originalRelevance = pojo.relevance;
            Integer historyValue = knownIds == null ? null : knownIds.get(pojo.id);
            if (historyValue != null && !pojo.isDisabled()) {
                originalRelevance += 25 * historyValue;
            }

            if (semanticEnabled && semanticRerank) {
                float semanticScore = SemanticEmbeddingScorer.score(query, pojo, semanticDimensions);
                pojo.relevance = hybridRelevance(pojo, originalRelevance, semanticScore, true);
            } else {
                pojo.relevance = originalRelevance;
                if (pojo.isDisabled()) pojo.relevance -= 200;
            }
        }
        return super.addResults(pojos);
    }

    @Override
    protected Void doInBackground(Void... voids) {
        MainActivity activity = activityWeakReference.get();
        if (activity == null) return null;

        List<ValuedHistoryRecord> lastIdsForQuery = DBHelper.getPreviousResultsForQuery(activity, query);
        knownIds = new HashMap<>();
        for (ValuedHistoryRecord id : lastIdsForQuery) knownIds.put(id.record, id.value);

        configureSemanticSearch();
        KissApplication.getApplication(activity).getDataHandler().requestResults(query, this);

        if (semanticEnabled) {
            semanticPass = true;
            KissApplication.getApplication(activity).getDataHandler().requestAllRecords(this);
            semanticPass = false;
        }
        return null;
    }

    private void configureSemanticSearch() {
        semanticEnabled = query != null
                && query.trim().length() >= 2
                && prefs.getBoolean("semantic-search-enabled", false)
                && SemanticEmbeddingScorer.MODEL_ID.equals(
                        prefs.getString("semantic-model", SemanticEmbeddingScorer.MODEL_ID));
        if (!semanticEnabled) return;

        semanticDimensions = parseIntPreference("semantic-embedding-dimensions", 128, 32, 512);
        // Previous default was 0.34. 0.26 admits more plausible candidates into the reranker while
        // final hybrid scoring prevents weak semantic noise from crowding the top of the list.
        semanticThreshold = parseFloatPreference("semantic-threshold", 0.26f, 0.05f, 0.95f);
        semanticRerank = prefs.getBoolean(PREF_SEMANTIC_RERANK, true);
        semanticWeight = parseFloatPreference(PREF_SEMANTIC_WEIGHT, 0.58f, 0.20f, 0.85f);
    }

    /**
     * Produce one comparable relevance scale for lexical and semantic candidates.
     *
     * Exact/prefix name matches get explicit protection. Semantic similarity can then promote a
     * conceptually strong result above weak fuzzy matches. Provider relevance and prior selections
     * are retained as bounded tie-breakers rather than being allowed to dominate an unrelated
     * semantic score simply because they happen to use a larger raw numeric scale.
     */
    private int hybridRelevance(Pojo pojo, int providerRelevance, float semanticScore,
                                boolean providerMatched) {
        float lexical = lexicalQuality(query, pojo, providerMatched);
        float provider = normalizeProviderRelevance(providerRelevance);
        float combined = (1f - semanticWeight) * lexical + semanticWeight * clamp01(semanticScore);
        combined += 0.12f * provider;

        String normalizedQuery = normalize(query);
        String normalizedName = normalize(pojo.getName());
        if (!normalizedQuery.isEmpty() && normalizedName.equals(normalizedQuery)) {
            combined += 0.38f;
        } else if (!normalizedQuery.isEmpty() && normalizedName.startsWith(normalizedQuery)) {
            combined += 0.22f;
        } else if (!normalizedQuery.isEmpty() && normalizedName.contains(normalizedQuery)) {
            combined += 0.10f;
        }

        Integer history = knownIds == null ? null : knownIds.get(pojo.id);
        if (history != null && history > 0) combined += Math.min(0.12f, history * 0.025f);

        int result = Math.round(combined * 10000f);
        if (pojo.isDisabled()) result -= 1200;
        return result;
    }

    private float lexicalQuality(String rawQuery, Pojo pojo, boolean providerMatched) {
        String q = normalize(rawQuery);
        if (q.isEmpty() || pojo == null) return 0f;

        String name = normalize(pojo.getName());
        String candidate = candidateText(pojo);
        if (name.equals(q)) return 1f;
        if (name.startsWith(q)) return 0.95f;
        if (containsTokenPrefix(name, q)) return 0.90f;
        if (name.contains(q)) return 0.86f;
        if (candidate.contains(q)) return 0.80f;

        String[] queryTokens = q.split("\\s+");
        boolean allTokens = true;
        int found = 0;
        for (String token : queryTokens) {
            if (token.isEmpty()) continue;
            if (candidate.contains(token)) found++;
            else allTokens = false;
        }
        if (allTokens && found > 0) return 0.78f;
        if (found > 0) return Math.min(0.68f, 0.36f + 0.10f * found);

        // A provider-returned fuzzy result gets some lexical credit, but deliberately less than a
        // strong semantic result. This is the key difference from the old raw-relevance ordering.
        return providerMatched ? 0.42f : 0f;
    }

    private String candidateText(Pojo pojo) {
        StringBuilder text = new StringBuilder();
        if (!TextUtils.isEmpty(pojo.getName())) text.append(pojo.getName()).append(' ');
        if (pojo instanceof PojoWithTags) {
            String tags = ((PojoWithTags) pojo).getTags();
            if (!TextUtils.isEmpty(tags)) text.append(tags).append(' ');
        }
        if (pojo instanceof AppPojo) {
            String packageName = ((AppPojo) pojo).packageName;
            if (!TextUtils.isEmpty(packageName)) text.append(packageName);
        }
        return normalize(text.toString());
    }

    private boolean containsTokenPrefix(String name, String query) {
        if (name.isEmpty() || query.isEmpty() || query.indexOf(' ') >= 0) return false;
        for (String token : name.split("\\s+")) {
            if (token.startsWith(query)) return true;
        }
        return false;
    }

    private float normalizeProviderRelevance(int relevance) {
        float positive = Math.max(0f, relevance);
        return positive <= 0f ? 0f : positive / (positive + 350f);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private int parseIntPreference(String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(prefs.getString(key, Integer.toString(fallback)));
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private float parseFloatPreference(String key, float fallback, float min, float max) {
        try {
            float value = Float.parseFloat(prefs.getString(key, Float.toString(fallback)));
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static void clearMaxResultCountCache() {
        MAX_RESULT_COUNT = -1;
    }
}
