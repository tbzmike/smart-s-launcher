from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1))

# -----------------------------------------------------------------------------
# Fuzzy search: add a real master switch. When disabled, matching is contiguous
# literal matching only; no FuzzyScoreV1/V2 and no typo-tolerance work runs.
# -----------------------------------------------------------------------------
replace_once(
    "app/src/main/java/fr/neamar/kiss/utils/fuzzy/FuzzyFactory.java",
'''public class FuzzyFactory {

    public static FuzzyScore createFuzzyScore(@NonNull Context context, int[] pattern) {
        return createFuzzyScore(context, pattern, false);
    }

    public static FuzzyScore createFuzzyScore(@NonNull Context context, int[] pattern, boolean detailedMatchIndices) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean("use-fuzzy-score-v1", false)) {
            return new FuzzyScoreV1(pattern, detailedMatchIndices);
        } else {
            return new FuzzyScoreV2(pattern, detailedMatchIndices);
        }
    }

}''',
'''public class FuzzyFactory {
    public static final String PREF_ENABLE_FUZZY_SEARCH = "enable-fuzzy-search";

    public static FuzzyScore createFuzzyScore(@NonNull Context context, int[] pattern) {
        return createFuzzyScore(context, pattern, false);
    }

    public static FuzzyScore createFuzzyScore(@NonNull Context context, int[] pattern, boolean detailedMatchIndices) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(PREF_ENABLE_FUZZY_SEARCH, false)) {
            return new LiteralScore(pattern, detailedMatchIndices);
        }
        if (prefs.getBoolean("use-fuzzy-score-v1", false)) {
            return new FuzzyScoreV1(pattern, detailedMatchIndices);
        } else {
            return new FuzzyScoreV2(pattern, detailedMatchIndices);
        }
    }

    public static boolean isFuzzyEnabled(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_ENABLE_FUZZY_SEARCH, false);
    }

}''')

literal_score = '''package fr.neamar.kiss.utils.fuzzy;

/**
 * Cheap contiguous literal matcher used when fuzzy search is disabled.
 * It deliberately does not perform subsequence/fuzzy matching: exact, prefix and contains are the
 * only accepted forms. This keeps ordinary search useful while honoring the fuzzy-search OFF state.
 */
final class LiteralScore implements FuzzyScore {
    private final int[] pattern;
    private final boolean detailedMatchIndices;

    LiteralScore(int[] pattern, boolean detailedMatchIndices) {
        this.pattern = pattern == null ? new int[0] : pattern.clone();
        this.detailedMatchIndices = detailedMatchIndices;
    }

    @Override public FuzzyScore setFullWordBonus(int value) { return this; }
    @Override public FuzzyScore setAdjacencyBonus(int value) { return this; }
    @Override public FuzzyScore setSeparatorBonus(int value) { return this; }
    @Override public FuzzyScore setCamelBonus(int value) { return this; }
    @Override public FuzzyScore setLeadingLetterPenalty(int value) { return this; }
    @Override public FuzzyScore setMaxLeadingLetterPenalty(int value) { return this; }
    @Override public FuzzyScore setUnmatchedLetterPenalty(int value) { return this; }
    @Override public FuzzyScore setFirstLetterBonus(int value) { return this; }

    @Override
    public MatchInfo match(CharSequence text) {
        if (text == null) return MatchInfo.UNMATCHED;
        int count = Character.codePointCount(text, 0, text.length());
        int[] codePoints = new int[count];
        int offset = 0;
        for (int i = 0; i < count; i++) {
            int cp = Character.codePointAt(text, offset);
            codePoints[i] = cp;
            offset += Character.charCount(cp);
        }
        return match(codePoints);
    }

    @Override
    public MatchInfo match(int[] text) {
        if (pattern.length == 0 || text == null || text.length < pattern.length) {
            return MatchInfo.UNMATCHED;
        }

        int matchStart = -1;
        outer:
        for (int start = 0; start <= text.length - pattern.length; start++) {
            for (int i = 0; i < pattern.length; i++) {
                if (Character.toLowerCase(text[start + i]) != Character.toLowerCase(pattern[i])) {
                    continue outer;
                }
            }
            matchStart = start;
            break;
        }
        if (matchStart < 0) return MatchInfo.UNMATCHED;

        int score;
        if (matchStart == 0 && text.length == pattern.length) score = 360;
        else if (matchStart == 0) score = 320;
        else if (isWordBoundary(text, matchStart)) score = 290 - Math.min(40, matchStart);
        else score = 250 - Math.min(80, matchStart);

        if (!detailedMatchIndices) return new MatchInfo(true, score);
        MatchInfo result = new MatchInfo(pattern.length);
        result.match = true;
        result.score = score;
        for (int i = 0; i < pattern.length; i++) result.matchedIndices.add(matchStart + i);
        return result;
    }

    private boolean isWordBoundary(int[] text, int index) {
        if (index <= 0) return true;
        int previous = text[index - 1];
        return !Character.isLetterOrDigit(previous);
    }
}
'''
Path("app/src/main/java/fr/neamar/kiss/utils/fuzzy/LiteralScore.java").write_text(literal_score)

replace_once(
    "app/src/main/java/fr/neamar/kiss/utils/fuzzy/SmartMatcher.java",
'''            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            this.typoToleranceEnabled = prefs.getBoolean("enable-typo-tolerance", true);

            List<String> expandedQueries = SmartSearch.expandQueries(context, query);''',
'''            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            this.typoToleranceEnabled = FuzzyFactory.isFuzzyEnabled(context)
                    && prefs.getBoolean("enable-typo-tolerance", true);

            List<String> expandedQueries = SmartSearch.expandQueries(context, query);''')

replace_once(
    "app/src/main/java/fr/neamar/kiss/dataprovider/simpleprovider/SettingsProvider.java",
'''import fr.neamar.kiss.utils.fuzzy.MatchInfo;
import fr.neamar.kiss.utils.fuzzy.SmartMatcher;''',
'''import fr.neamar.kiss.utils.fuzzy.FuzzyFactory;
import fr.neamar.kiss.utils.fuzzy.MatchInfo;
import fr.neamar.kiss.utils.fuzzy.SmartMatcher;''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/dataprovider/simpleprovider/SettingsProvider.java",
'''                if (!match) {
                    matchInfo = fr.neamar.kiss.utils.fuzzy.TypoTolerance.match(context, query, settingName);
                    match = pojo.updateMatchingRelevance(matchInfo, match);
                }''',
'''                if (!match && FuzzyFactory.isFuzzyEnabled(context)) {
                    matchInfo = fr.neamar.kiss.utils.fuzzy.TypoTolerance.match(context, query, settingName);
                    match = pojo.updateMatchingRelevance(matchInfo, match);
                }''')

# App semantic hints are semantic behavior and must obey the same master semantic switch.
replace_once(
    "app/src/main/java/fr/neamar/kiss/dataprovider/AppProvider.java",
'''import java.util.ArrayList;
import java.util.List;''',
'''import java.util.ArrayList;
import java.util.Collections;
import java.util.List;''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/dataprovider/AppProvider.java",
'''        Set<String> excludedFavoriteIds = KissApplication.getApplication(this).getDataHandler().getExcludedFavorites();
        List<String> semanticHints = SemanticHints.expand(query);''',
'''        Set<String> excludedFavoriteIds = KissApplication.getApplication(this).getDataHandler().getExcludedFavorites();
        List<String> semanticHints = prefs.getBoolean("semantic-search-enabled", false)
                ? SemanticHints.expand(query)
                : Collections.emptyList();''')

# Add an explicit fuzzy master switch. Preserve the old V1/V2 selector, now correctly dependent on it.
replace_once(
    "app/src/main/res/xml/preferences.xml",
'''        <SwitchPreference
            app:defaultValue="false"
            app:key="use-fuzzy-score-v1"
            app:title="Use legacy fuzzy search algorithm" />''',
'''        <SwitchPreference
            app:defaultValue="false"
            app:key="enable-fuzzy-search"
            app:summary="Off uses fast literal matching only. Turn on for non-contiguous fuzzy matching and typo tolerance."
            app:title="Enable fuzzy search" />
        <SwitchPreference
            app:defaultValue="false"
            app:dependency="enable-fuzzy-search"
            app:key="use-fuzzy-score-v1"
            app:title="Use legacy fuzzy search algorithm" />''')

# -----------------------------------------------------------------------------
# History-first query stage: reuse the already-rendered history POJOs in memory.
# No DB/provider re-resolution is performed for the preview.
# -----------------------------------------------------------------------------
replace_once(
    "app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java",
'''    public boolean showNotificationHistoryIfAvailable(final int pos, View v) {''',
'''    /** Snapshot the already-loaded rows for a cheap history-first query stage. */
    @NonNull
    public List<Pojo> snapshotPojos() {
        List<Pojo> snapshot = new ArrayList<>(results.size());
        for (Result<?> result : results) {
            if (result != null && result.getPojo() != null) snapshot.add(result.getPojo());
        }
        return snapshot;
    }

    public boolean showNotificationHistoryIfAvailable(final int pos, View v) {''')

replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/Searcher.java",
'''    /** Publish a stable snapshot without ending the active search. */
    protected final void publishCurrentResults() {''',
'''    /** Publish a preview list without adding it to the final provider result queue. */
    protected final void publishPreviewResults(List<? extends Pojo> previewPojos) {
        if (isCancelled() || previewPojos == null || previewPojos.isEmpty()) return;
        MainActivity activity = activityWeakReference.get();
        if (activity == null) return;

        int maxResults = Math.max(0, getMaxResultCount());
        int from = Math.max(0, previewPojos.size() - maxResults);
        List<Pojo> snapshot = new ArrayList<>(previewPojos.size() - from);
        for (int i = from; i < previewPojos.size(); i++) {
            Pojo pojo = previewPojos.get(i);
            if (pojo != null) snapshot.add(pojo);
        }
        if (snapshot.isEmpty()) return;

        activity.runOnUiThread(() -> {
            if (isCancelled()) return;
            MainActivity currentActivity = activityWeakReference.get();
            if (currentActivity == null) return;
            currentActivity.adapter.updateWithPojos(currentActivity, snapshot, true, query);
        });
    }

    /** Publish a stable snapshot without ending the active search. */
    protected final void publishCurrentResults() {''')

replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/SearchHandler.java",
'''import androidx.annotation.NonNull;

import fr.neamar.kiss.MainActivity;''',
'''import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.pojo.Pojo;''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/SearchHandler.java",
'''    /** Running search task. */
    private Searcher runningSearch;''',
'''    /** Running search task. */
    private Searcher runningSearch;

    /** Last successfully rendered history rows, reused as the zero-DB first query stage. */
    private List<Pojo> historyQuerySeed = Collections.emptyList();''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/SearchHandler.java",
'''        SmartMatcher.beginSearch();
        runningSearch = createSearcher(type, activity, query, isRefresh);
        runningSearch.setSearchDoneCallback((searcher, isCancelled) -> {
            if (runningSearch == searcher) resetRunningSearch();
        });''',
'''        SmartMatcher.beginSearch();
        final Searcher.Type startedType = type;
        runningSearch = createSearcher(type, activity, query, isRefresh);
        runningSearch.setSearchDoneCallback((searcher, isCancelled) -> {
            if (!isCancelled && startedType == Searcher.Type.HISTORY && activity.adapter != null) {
                historyQuerySeed = activity.adapter.snapshotPojos();
            }
            if (runningSearch == searcher) resetRunningSearch();
        });''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/SearchHandler.java",
'''            case QUERY:
                return new QuerySearcher(activity, query, isRefresh);''',
'''            case QUERY:
                return new QuerySearcher(activity, query, isRefresh,
                        new ArrayList<>(historyQuerySeed));''')

replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/QuerySearcher.java",
'''    private final Set<String> lexicalIds = new HashSet<>();
    private boolean semanticPass;''',
'''    private final Set<String> lexicalIds = new HashSet<>();
    private final List<Pojo> historySeed;
    private final List<Pojo> historyMatches = new ArrayList<>();
    private boolean semanticPass;''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/QuerySearcher.java",
'''    public QuerySearcher(MainActivity activity, String query, boolean isRefresh) {
        super(activity, query, isRefresh);
        prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }''',
'''    public QuerySearcher(MainActivity activity, String query, boolean isRefresh,
                         List<Pojo> historySeed) {
        super(activity, query, isRefresh);
        prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        this.historySeed = historySeed == null ? new ArrayList<>() : new ArrayList<>(historySeed);
    }''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/QuerySearcher.java",
'''        List<ValuedHistoryRecord> lastIdsForQuery = DBHelper.getPreviousResultsForQuery(activity, query);
        knownIds = new HashMap<>();
        for (ValuedHistoryRecord id : lastIdsForQuery) knownIds.put(id.record, id.value);

        configureSemanticSearch();
        KissApplication.getApplication(activity).getDataHandler().requestResults(query, this);

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
        return null;''',
'''        // First stage: search the already-rendered history rows in memory. This happens before
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
        return null;''')

# Insert history preview helpers before configureSemanticSearch().
replace_once(
    "app/src/main/java/fr/neamar/kiss/searcher/QuerySearcher.java",
'''    private void configureSemanticSearch() {''',
'''    private void prepareHistoryPreview(MainActivity activity) {
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

    private void configureSemanticSearch() {''')

# -----------------------------------------------------------------------------
# Version bump only. Normal APK workflow is updated separately after guarded
# verification so a failed patch cannot rewrite the production build workflow.
# -----------------------------------------------------------------------------
replace_once(
    "app/build.gradle",
'''        // Smart S Launcher 3.30.11
        versionCode 439
        versionName "3.30.11"''',
'''        // Smart S Launcher 3.30.12
        versionCode 440
        versionName "3.30.12"''')
