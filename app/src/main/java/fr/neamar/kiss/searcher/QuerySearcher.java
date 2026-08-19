package fr.neamar.kiss.searcher;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ValuedHistoryRecord;
import fr.neamar.kiss.pojo.Pojo;

/**
 * AsyncTask retrieving data from the providers and updating the view
 *
 * @author dorvaryn
 */
public class QuerySearcher extends Searcher {
    private static int MAX_RESULT_COUNT = -1;
    private HashMap<String, Integer> knownIds;
    /**
     * Store user preferences
     */
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
            // Convert `"number-of-display-elements"` to double first before truncating to int to avoid
            // `java.lang.NumberFormatException` crashes for values larger than `Integer.MAX_VALUE`
            try {
                MAX_RESULT_COUNT = Double.valueOf(prefs.getString("number-of-display-elements", String.valueOf(DEFAULT_MAX_RESULTS))).intValue();
            } catch (NumberFormatException e) {
                // If, for any reason, setting is empty, return default value.
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

                // Semantic matches are a fallback layer. Strong literal/fuzzy matches from providers
                // keep their normal priority, while useful concept matches can still enter the list.
                pojo.relevance = Math.max(pojo.relevance, 120 + Math.round(score * 280f));
                if (pojo.isDisabled()) pojo.relevance -= 200;
                semanticMatches.add(pojo);
            }
            return super.addResults(semanticMatches);
        }

        for (Pojo pojo : pojos) {
            lexicalIds.add(pojo.id);
            if (pojo.isDisabled()) {
                // Give penalty for disabled items, these should not be preferred
                pojo.relevance -= 200;
            } else {
                // Give a boost if item was previously selected for this query
                Integer value = knownIds.get(pojo.id);
                if (value != null) {
                    pojo.relevance += 25 * value;
                }
            }
        }

        // call super implementation to update the adapter
        return super.addResults(pojos);
    }

    /**
     * Called on the background thread
     */
    @Override
    protected Void doInBackground(Void... voids) {
        MainActivity activity = activityWeakReference.get();
        if (activity == null)
            return null;

        // Have we ever made the same query and selected something ?
        List<ValuedHistoryRecord> lastIdsForQuery = DBHelper.getPreviousResultsForQuery(activity, query);
        knownIds = new HashMap<>();
        for (ValuedHistoryRecord id : lastIdsForQuery) {
            knownIds.put(id.record, id.value);
        }

        KissApplication.getApplication(activity).getDataHandler().requestResults(query, this);

        if (query != null && query.length() >= 2 && prefs.getBoolean("semantic-search-enabled", false)) {
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
