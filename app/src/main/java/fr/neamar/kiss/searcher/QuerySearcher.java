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
import fr.neamar.kiss.pojo.SearchPojo;
import fr.neamar.kiss.pojo.SearchPojoType;

/**
 * AsyncTask retrieving data from the providers and updating the view.
 *
 * Semantic mode uses a hybrid reranker instead of treating embeddings as a weak fallback. Exact
 * lexical matches remain protected, while strong semantic matches can outrank weak fuzzy matches.
 *
 * Normal lexical/fuzzy mode also keeps local indexed results on a stable relevance scale. Fuzzy
 * MatchInfo scores are intentionally pattern-dependent, so they must not be compared directly with
 * fixed relevance constants used by generic web/app-store search actions.
 */
public class QuerySearcher extends Searcher {
    public static final String PREF_SEMANTIC_RERANK = "semantic-rerank-enabled";
    public static final String PREF_SEMANTIC_WEIGHT = "semantic-rerank-weight";

    private static int MAX_RESULT_COUNT = -1;
    private HashMap<String, Integer> knownIds;
    private final SharedPreferences prefs;
    private final Set<String> lexicalIds = new HashSet<>();
    private final List<Pojo> historySeed;
    private final List<Pojo> historyMatches = new ArrayList<>();
    private boolean semanticPass;
    private boolean semanticEnabled;
    private boolean semanticRerank;
    private float semanticThreshold;
    private float semanticWeight;
    private int semanticDimensions;
    private float[] preparedSemanticQuery;

    public QuerySearcher(MainActivity activity, String query, boolean isRefresh,
                         List<Pojo> historySeed) {
        super(activity, query, isRefresh);
        prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        this.historySeed = historySeed == null ? new ArrayList<>() : new ArrayList<>(historySeed);
    }

    @Override
    protected int getMaxResultCount() {
        if (MAX_RESULT_COUNT == -1) {
            try {
                MAX_RESULT_COUNT = Double.valueOf(prefs.getString("number-of-display-elements",
                        String.valueOf(DEFAULT_MAX_RESULTS))).intValue();
            } catch (NumberFormatException | ClassCastException e) {
                MAX_RESULT_COUNT = DEFAULT_MAX_RESULTS;
            }
        }
        return MAX_RESULT_COUNT;
    }

    @Override
    public boolean addResults(List<? extends Pojo> pojos) {
        if (semanticPass) {
            List<Pojo> semanticMatches = new ArrayList<>();
            int checked = 0;
            for (Pojo pojo : pojos) {
                if ((checked++ & 31) == 0 && isCancelled()) return false;
                if (pojo == null || lexicalIds.contains(pojo.id)) continue;
                float score = SemanticEmbeddingScorer.scorePrepared(preparedSemanticQuery, pojo);
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

            if (semanticEnabled && semanticRerank) {
                if (historyValue != null && !pojo.isDisabled()) {
                    originalRelevance += 25 * historyValue;
                }
                float semanticScore = SemanticEmbeddingScorer.scorePrepared(preparedSemanticQuery, pojo);
                pojo.relevance = hybridRelevance(pojo, originalRelevance, semanticScore, true);
            } else if (pojo instanceof SearchPojo) {
                // SearchPojo relevance is deliberately authored by SearchProvider. Preserve explicit
                // commands/actions and passive external-search suggestions exactly as authored.
                // Local indexed records are normalized separately below so a raw fuzzy score can no
                // longer accidentally fall beneath a passive Search YouTube/Play Store suggestion.
                pojo.relevance = originalRelevance;
            } else {
                // MatchInfo.score explicitly has no fixed global range. Map every genuine local
                // provider match monotonically into a stable band above passive external searches.
                // This preserves ordering among local fuzzy matches while making result classes
                // comparable. Range is approximately -60..260 before history/disabled adjustments.
                pojo.relevance = normalizeLocalLexicalRelevance(originalRelevance);
                if (historyValue != null && !pojo.isDisabled()) {
                    pojo.relevance += Math.min(150, 25 * historyValue);
                }
                if (pojo.isDisabled()) pojo.relevance -= 200;
            }
        }
        return super.addResults(pojos);
    }

    @Override
    protected Void doInBackground(Void... voids) {
        MainActivity activity = activityWeakReference.get();
        if (activity == null) return null;

        // First stage: search the already-rendered history rows in memory. This happens before
        // any DB query, provider scan, fuzzy scorer or embedding work.
        prepareHistoryPreview(activity);
        if (isCancelled()) return null;

        List<ValuedHistoryRecord> lastIdsForQuery = DBHelper.getPreviousResultsForQuery(activity, query);
        knownIds = new HashMap<>();
        for (ValuedHistoryRecord id : lastIdsForQuery) knownIds.put(id.record, id.value);

        configureSemanticSearch();
        KissApplication.getApplication(activity).getDataHandler().requestResults(query, this);
        mergeMissingHistoryMatches();

        // Do not hold useful lexical matches behind the full semantic-record scan. The same
        // Searcher remains active, so the deeper pass can still improve the final ranking and is
        // cancelled immediately if the user types again or chooses a result.
        if (semanticEnabled && !lexicalIds.isEmpty() && !isCancelled()) {
            publishCurrentResults();
        }

        if (semanticEnabled && !isCancelled()) {
            semanticPass = true;
            KissApplication.getApplication(activity).getDataHandler().requestAllRecords(this);
            semanticPass = false;
        }
        return null;
    }

    private void prepareHistoryPreview(MainActivity activity) {
        historyMatches.clear();
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty() || historySeed.isEmpty()) return;

        Set<String> excludedFavoriteIds = KissApplication.getApplication(activity)
                .getDataHandler().getExcludedFavorites();
        boolean detectFrozen = prefs.getBoolean("smart-detect-frozen-apps", true);
        boolean keepFrozenSearchable = prefs.getBoolean("smart-keep-frozen-searchable", true);
        boolean enableExcludedApps = prefs.getBoolean("enable-excluded-apps", false);

        int checked = 0;
        for (Pojo pojo : historySeed) {
            if ((checked++ & 31) == 0 && isCancelled()) return;
            if (pojo == null || excludedFavoriteIds.contains(pojo.getFavoriteId())) continue;
            if (pojo instanceof AppPojo) {
                AppPojo app = (AppPojo) pojo;
                if (app.isExcluded() && !enableExcludedApps) continue;
                if (app.isDisabled() && (!detectFrozen || !keepFrozenSearchable)) continue;
            }

            String name = normalize(pojo.getName());
            String candidate = candidateText(pojo);
            boolean obvious = name.startsWith(normalizedQuery)
                    || containsTokenPrefix(name, normalizedQuery);
            if (!obvious && normalizedQuery.length() >= 2) {
                obvious = candidate.contains(normalizedQuery);
            }
            if (obvious) historyMatches.add(pojo);
        }

        if (!historyMatches.isEmpty() && !isCancelled()) publishPreviewResults(historyMatches);
    }

    /** Keep history-only matches in the final set without duplicating provider matches. */
    private void mergeMissingHistoryMatches() {
        if (historyMatches.isEmpty() || isCancelled()) return;
        List<Pojo> missing = new ArrayList<>();
        int count = historyMatches.size();
        for (int i = 0; i < count; i++) {
            Pojo pojo = historyMatches.get(i);
            if (pojo == null || lexicalIds.contains(pojo.id)) continue;
            pojo.relevance = 180 + Math.round(80f * (i + 1) / Math.max(1, count));
            if (pojo.isDisabled()) pojo.relevance -= 200;
            lexicalIds.add(pojo.id);
            missing.add(pojo);
        }
        if (!missing.isEmpty()) super.addResults(missing);
    }

    private void configureSemanticSearch() {
        semanticEnabled = query != null
                && query.trim().length() >= 2
                && prefs.getBoolean("semantic-search-enabled", false)
                && SemanticEmbeddingScorer.MODEL_ID.equals(
                        getStringPreference("semantic-model", SemanticEmbeddingScorer.MODEL_ID));
        if (!semanticEnabled) return;

        semanticDimensions = parseIntPreference("semantic-embedding-dimensions", 128, 32, 512);
        preparedSemanticQuery = SemanticEmbeddingScorer.prepareQuery(query, semanticDimensions);
        semanticThreshold = parseFloatPreference("semantic-threshold", 0.26f, 0.05f, 0.95f);
        semanticRerank = prefs.getBoolean(PREF_SEMANTIC_RERANK, true);
        semanticWeight = parseFloatPreference(PREF_SEMANTIC_WEIGHT, 0.58f, 0.20f, 0.85f);
    }

    private int normalizeLocalLexicalRelevance(int rawRelevance) {
        // Monotonic compression: no assumptions about FuzzyScoreV1/V2 numeric range, while retaining
        // their ordering for one query. Passive configured search providers are currently -500, so
        // even the low end of this local band stays ahead of them (including the disabled penalty).
        double normalized = Math.tanh(rawRelevance / 350.0d);
        return 100 + (int) Math.round(160.0d * normalized);
    }

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

    private String getStringPreference(String key, String fallback) {
        try {
            return prefs.getString(key, fallback);
        } catch (ClassCastException e) {
            return fallback;
        }
    }

    private int parseIntPreference(String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(prefs.getString(key, Integer.toString(fallback)));
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException | ClassCastException e) {
            return fallback;
        }
    }

    private float parseFloatPreference(String key, float fallback, float min, float max) {
        try {
            float value = Float.parseFloat(prefs.getString(key, Float.toString(fallback)));
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException | ClassCastException e) {
            return fallback;
        }
    }

    public static void clearMaxResultCountCache() {
        MAX_RESULT_COUNT = -1;
    }
}
