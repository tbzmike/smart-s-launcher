package fr.neamar.kiss.searcher;

import android.content.SharedPreferences;

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
import fr.neamar.kiss.pojo.Pojo;

public class QuerySearcher extends Searcher {
    private static int MAX_RESULT_COUNT = -1;
    private HashMap<String, Integer> knownIds;
    private final SharedPreferences prefs;
    private final Set<String> lexicalIds = new HashSet<>();
    private boolean semanticPass;
    private float semanticThreshold;
    private int semanticDimensions;

    public QuerySearcher(MainActivity activity, String query, boolean isRefresh) {
        super(activity, query, isRefresh);
        prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    @Override
    protected int getMaxResultCount() {
        if (MAX_RESULT_COUNT == -1) {
            try { MAX_RESULT_COUNT = Double.valueOf(prefs.getString("number-of-display-elements", String.valueOf(DEFAULT_MAX_RESULTS))).intValue(); }
            catch (NumberFormatException e) { MAX_RESULT_COUNT = DEFAULT_MAX_RESULTS; }
        }
        return MAX_RESULT_COUNT;
    }

    @Override
    public boolean addResults(List<? extends Pojo> pojos) {
        if (semanticPass) {
            List<Pojo> matches = new ArrayList<>();
            for (Pojo pojo : pojos) {
                if (pojo == null || lexicalIds.contains(pojo.id)) continue;
                float score = SemanticEmbeddingScorer.score(query, pojo, semanticDimensions);
                if (score < semanticThreshold) continue;
                pojo.relevance = Math.max(pojo.relevance, 120 + Math.round(score * 280f));
                if (pojo.isDisabled()) pojo.relevance -= 200;
                matches.add(pojo);
            }
            return super.addResults(matches);
        }

        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (Pojo pojo : pojos) {
            if (pojo == null) continue;
            lexicalIds.add(pojo.id);
            String name = pojo.getName() == null ? "" : pojo.getName().trim().toLowerCase(Locale.ROOT);
            // Exact and prefix hits should always beat generic web/search actions.
            if (!q.isEmpty()) {
                if (name.equals(q)) pojo.relevance += 1200;
                else if (name.startsWith(q)) pojo.relevance += 700;
                else if (name.contains(q)) pojo.relevance += 350;
            }
            if (pojo.isDisabled()) {
                pojo.relevance -= 200;
            } else {
                Integer value = knownIds.get(pojo.id);
                if (value != null) pojo.relevance += 25 * value;
            }
        }
        return super.addResults(pojos);
    }

    @Override
    protected Void doInBackground(Void... voids) {
        MainActivity activity = activityWeakReference.get();
        if (activity == null) return null;
        List<ValuedHistoryRecord> previous = DBHelper.getPreviousResultsForQuery(activity, query);
        knownIds = new HashMap<>();
        for (ValuedHistoryRecord id : previous) knownIds.put(id.record, id.value);

        KissApplication.getApplication(activity).getDataHandler().requestResults(query, this);

        // Semantic search is deliberately a fallback. Avoid its full-record scoring pass when
        // lexical providers already filled the requested result window.
        if (query != null && query.length() >= 2 && lexicalIds.size() < getMaxResultCount() && prefs.getBoolean("semantic-search-enabled", false)) {
            String model = prefs.getString("semantic-model", SemanticEmbeddingScorer.MODEL_ID);
            if (SemanticEmbeddingScorer.MODEL_ID.equals(model)) {
                semanticDimensions = parseIntPreference("semantic-embedding-dimensions", 128, 32, 512);
                semanticThreshold = parseFloatPreference("semantic-threshold", 0.34f, 0.05f, 0.95f);
                semanticPass = true;
                KissApplication.getApplication(activity).getDataHandler().requestAllRecords(this);
                semanticPass = false;
            }
        }
        return null;
    }

    private int parseIntPreference(String key, int fallback, int min, int max) {
        try { int value = Integer.parseInt(prefs.getString(key, Integer.toString(fallback))); return Math.max(min, Math.min(max, value)); }
        catch (NumberFormatException e) { return fallback; }
    }

    private float parseFloatPreference(String key, float fallback, float min, float max) {
        try { float value = Float.parseFloat(prefs.getString(key, Float.toString(fallback))); return Math.max(min, Math.min(max, value)); }
        catch (NumberFormatException e) { return fallback; }
    }

    public static void clearMaxResultCountCache() { MAX_RESULT_COUNT = -1; }
}
