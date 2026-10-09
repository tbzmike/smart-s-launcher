package fr.neamar.kiss.searcher;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.db.AppSourceMetadataRecord;
import fr.neamar.kiss.db.SemanticActivityRecord;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.SearchPojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.utils.Log;

/**
 * In-memory hierarchical navigable small-world index for launcher semantic search.
 *
 * <p>The expensive candidate vectors and graph are built away from the interactive query worker.
 * Query time embeds only the typed text, walks the HNSW graph, and returns a small nearest-neighbour
 * set for QuerySearcher to hybrid-rerank. Provider reloads coalesce into one replacement snapshot,
 * so a query always sees either the old complete graph or the new complete graph, never a partial
 * index.</p>
 */
public final class SemanticHnswIndex {
    private static final String TAG = SemanticHnswIndex.class.getSimpleName();

    public static final String PREF_HNSW_ENABLED = "semantic-hnsw-enabled";
    public static final String PREF_HNSW_EF_SEARCH = "semantic-hnsw-ef-search";
    public static final String PREF_EMBEDDING_DIMENSIONS = "semantic-embedding-dimensions";

    public static final int DEFAULT_DIMENSIONS = 384;
    private static final int MIN_DIMENSIONS = 256;
    private static final int MAX_DIMENSIONS = 400;
    private static final String PREF_DIMENSION_UPGRADE_DONE =
            "semantic-embedding-dimensions-384-upgrade-done";

    private static final int MAX_LEVEL = 8;
    private static final int DEFAULT_EF_SEARCH = 80;
    private static final int MIN_EF_SEARCH = 24;
    private static final int MAX_EF_SEARCH = 256;
    private static final String PREF_HNSW_TUNING_UPGRADE_DONE =
            "semantic-hnsw-384-tuning-upgrade-done";

    private static final SemanticHnswIndex INSTANCE = new SemanticHnswIndex();

    private final ExecutorService buildExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "smart-s-semantic-hnsw-build");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final AtomicInteger requestedGeneration = new AtomicInteger();

    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile boolean building;
    private volatile long lastBuildMs;
    private volatile long lastLookupMs;
    private volatile int lastSourceCount;

    private SemanticHnswIndex() { }

    public static SemanticHnswIndex getInstance() {
        return INSTANCE;
    }

    /**
     * Coalesce provider/settings changes into a background rebuild. The current complete graph stays
     * queryable until the replacement has finished.
     */
    public void scheduleRebuild(@NonNull DataHandler dataHandler,
                                @NonNull SharedPreferences prefs) {
        if (!prefs.getBoolean("semantic-search-enabled", false)
                || !prefs.getBoolean(PREF_HNSW_ENABLED, true)) {
            requestedGeneration.incrementAndGet();
            snapshot = Snapshot.empty();
            building = false;
            return;
        }

        final int dimensions = parseDimensions(prefs);
        final int generation = requestedGeneration.incrementAndGet();
        final boolean useSourceDescriptions =
                prefs.getBoolean(AppSourceMetadataUpdater.PREF_USE_SOURCE_DESCRIPTIONS, true);

        // Mark queued work immediately. onResume()/LOAD_OVER can arrive back-to-back and must not
        // enqueue duplicate snapshots while the launcher is rendering its first frame.
        building = true;

        buildExecutor.execute(() -> {
            if (generation != requestedGeneration.get()) return;

            long startNs = System.nanoTime();
            try {
                // Provider snapshots and SQLite metadata reads stay entirely off the Home/UI path.
                // The currently complete graph remains queryable until this replacement is ready.
                final List<Pojo> source = dataHandler.getSemanticIndexSnapshot();
                final Map<String, AppSourceMetadataRecord> metadataByPackage =
                        useSourceDescriptions
                                ? dataHandler.getAppSourceMetadataRecords()
                                : Collections.emptyMap();
                final Map<String, String> sourceTextByPackage = new HashMap<>();
                for (Map.Entry<String, AppSourceMetadataRecord> entry
                        : metadataByPackage.entrySet()) {
                    AppSourceMetadataRecord record = entry.getValue();
                    if (record == null) continue;
                    String text = record.semanticText();
                    if (text != null && !text.trim().isEmpty()) {
                        sourceTextByPackage.put(entry.getKey(), text);
                    }
                }
                lastSourceCount = source.size();
                final String sessionId = "hnsw-" + generation + "-" + System.currentTimeMillis();
                try {
                    dataHandler.logSemanticActivity(new SemanticActivityRecord(
                            System.currentTimeMillis(),
                            "HNSW_BUILD_STARTED",
                            sessionId,
                            "",
                            "",
                            "HNSW",
                            "Started semantic HNSW build from " + source.size()
                                    + " records · " + dimensions
                                    + " dimensions · graph M "
                                    + connectionsForDimensions(dimensions)
                                    + " · construction ef "
                                    + constructionEfForDimensions(dimensions)
                                    + " · metadata descriptions "
                                    + sourceTextByPackage.size() + "."));
                } catch (RuntimeException logError) {
                    Log.w(TAG, "Unable to record HNSW build start", logError);
                }

                if (generation != requestedGeneration.get()) return;

                List<SemanticActivityRecord> indexEvents = new ArrayList<>();
                Snapshot built = buildSnapshot(
                        source,
                        dimensions,
                        generation,
                        sourceTextByPackage,
                        metadataByPackage,
                        sessionId,
                        indexEvents);
                long elapsedMs = nanosToMs(System.nanoTime() - startNs);

                if (generation != requestedGeneration.get()) return;
                snapshot = built;
                lastBuildMs = elapsedMs;
                indexEvents.add(new SemanticActivityRecord(
                        System.currentTimeMillis(),
                        "HNSW_BUILD_COMPLETED",
                        sessionId,
                        "",
                        "",
                        "HNSW",
                        "Committed " + built.nodes.size() + " vectors from " + source.size()
                                + " source records · " + dimensions + " dimensions · graph M "
                                + built.maxConnections + " · construction ef "
                                + built.constructionEf + " · " + elapsedMs + " ms."));
                try {
                    dataHandler.logSemanticActivities(indexEvents);
                } catch (RuntimeException logError) {
                    Log.w(TAG, "HNSW index is ready but activity logging failed", logError);
                }
                Log.i(TAG, "HNSW semantic index ready: " + built.nodes.size()
                        + " vectors, " + dimensions + " dimensions, " + elapsedMs + "ms");
            } catch (RuntimeException e) {
                if (generation == requestedGeneration.get()) {
                    try {
                        dataHandler.logSemanticActivity(new SemanticActivityRecord(
                                System.currentTimeMillis(),
                                "HNSW_BUILD_FAILED",
                                "hnsw-" + generation,
                                "",
                                "",
                                "HNSW",
                                e.getClass().getSimpleName() + ": "
                                        + (e.getMessage() == null ? "build failed" : e.getMessage())));
                    } catch (RuntimeException logError) {
                        Log.w(TAG, "Unable to record HNSW build failure", logError);
                    }
                    Log.w(TAG, "HNSW semantic index rebuild failed; retaining previous graph", e);
                }
            } finally {
                if (generation == requestedGeneration.get()) building = false;
            }
        });
    }

    /**
     * Schedule only when the current graph cannot serve the requested dimensions.
     */
    public void ensureReady(@NonNull DataHandler dataHandler,
                            @NonNull SharedPreferences prefs,
                            int dimensions) {
        Snapshot current = snapshot;
        if (current.dimensions == dimensions && !current.nodes.isEmpty()) return;
        if (!building) scheduleRebuild(dataHandler, prefs);
    }

    @NonNull
    public List<Hit> search(@NonNull float[] queryVector,
                            int requestedCount,
                            int requestedEfSearch) {
        Snapshot current = snapshot;
        if (queryVector.length == 0
                || current.nodes.isEmpty()
                || current.dimensions != queryVector.length) {
            lastLookupMs = 0L;
            return Collections.emptyList();
        }

        long startNs = System.nanoTime();
        int count = Math.max(1, Math.min(requestedCount, current.nodes.size()));
        int ef = clamp(requestedEfSearch, MIN_EF_SEARCH, MAX_EF_SEARCH);
        ef = Math.max(ef, count);

        int entry = current.entryPoint;
        float entryScore = dot(queryVector, current.nodes.get(entry).vector);

        for (int level = current.maxLevel; level > 0; level--) {
            Candidate improved = greedyClosest(current, queryVector, entry, entryScore, level);
            entry = improved.index;
            entryScore = improved.score;
        }

        List<Candidate> nearest = searchLayer(
                current, queryVector, Collections.singletonList(entry), ef, 0);
        nearest.sort((left, right) -> Float.compare(right.score, left.score));

        List<Hit> hits = new ArrayList<>(Math.min(count, nearest.size()));
        for (int i = 0; i < nearest.size() && hits.size() < count; i++) {
            Candidate candidate = nearest.get(i);
            Node node = current.nodes.get(candidate.index);
            hits.add(new Hit(node.pojo, candidate.score));
        }

        lastLookupMs = nanosToMs(System.nanoTime() - startNs);
        return hits;
    }

    public boolean isReadyFor(int dimensions) {
        Snapshot current = snapshot;
        return !current.nodes.isEmpty() && current.dimensions == dimensions;
    }

    public boolean isBuilding() {
        return building;
    }

    public int indexedCount() {
        return snapshot.nodes.size();
    }

    public int dimensions() {
        return snapshot.dimensions;
    }

    public long lastBuildMs() {
        return lastBuildMs;
    }

    public long lastLookupMs() {
        return lastLookupMs;
    }

    public int lastSourceCount() {
        return lastSourceCount;
    }

    public String statusSummary() {
        Snapshot current = snapshot;
        if (building) {
            return "Building HNSW index… current ready vectors: " + current.nodes.size()
                    + " · source records: " + lastSourceCount;
        }
        if (current.nodes.isEmpty()) {
            return "HNSW index not ready yet";
        }
        return "Ready · " + current.nodes.size() + " vectors · "
                + current.dimensions + " dimensions · graph M " + current.maxConnections
                + " · construction ef " + current.constructionEf
                + " · build " + lastBuildMs
                + " ms · last lookup " + lastLookupMs + " ms";
    }

    private Snapshot buildSnapshot(List<Pojo> source,
                                   int dimensions,
                                   int generation,
                                   Map<String, String> sourceTextByPackage,
                                   Map<String, AppSourceMetadataRecord> metadataByPackage,
                                   String sessionId,
                                   List<SemanticActivityRecord> indexEvents) {
        MutableGraph graph = new MutableGraph(dimensions);
        Set<String> seenIds = new HashSet<>(Math.max(16, source.size() * 2));

        for (int i = 0; i < source.size(); i++) {
            if ((i & 31) == 0 && generation != requestedGeneration.get()) {
                return Snapshot.empty();
            }

            Pojo pojo = source.get(i);
            if (pojo == null || pojo instanceof SearchPojo || pojo.id == null
                    || !seenIds.add(pojo.id)) {
                continue;
            }

            String packageName = packageForPojo(pojo);
            String sourceText = sourceTextForPojo(pojo, sourceTextByPackage);
            float[] vector = SemanticEmbeddingScorer.prepareCandidate(
                    pojo,
                    dimensions,
                    sourceText);
            if (isZero(vector)) continue;
            insert(graph, pojo, vector);

            // Keep per-record transparency focused on launch targets. Logging every contact,
            // message and provider record created thousands of low-value rows and hid the app
            // information the user actually needs. The build-complete event still reports the
            // full vector count for all indexed record types.
            if (pojo instanceof AppPojo || pojo instanceof ShortcutPojo) {
                AppSourceMetadataRecord metadata = packageName == null
                        ? null : metadataByPackage.get(packageName);
                String eventType = pojo instanceof AppPojo
                        ? "HNSW_APP_INDEXED" : "HNSW_SHORTCUT_INDEXED";
                String sourceName = metadata == null || metadata.source == null
                        || metadata.source.isEmpty() ? "Local app identity" : metadata.source;
                int metadataChars = metadata == null || metadata.description == null
                        ? 0 : metadata.description.length();
                indexEvents.add(new SemanticActivityRecord(
                        System.currentTimeMillis(),
                        eventType,
                        sessionId,
                        packageName == null ? "" : packageName,
                        pojo.getName() == null ? "" : pojo.getName(),
                        sourceName,
                        "Indexed " + dimensions + "-dimension semantic vector"
                                + (metadataChars > 0
                                        ? " with " + metadataChars
                                                + " description characters from " + sourceName
                                        : " without downloaded description metadata")
                                + "."));
            }
        }

        return graph.freeze();
    }

    @Nullable
    private static String packageForPojo(Pojo pojo) {
        if (pojo instanceof AppPojo) return ((AppPojo) pojo).packageName;
        if (pojo instanceof ShortcutPojo) return ((ShortcutPojo) pojo).packageName;
        return null;
    }

    @Nullable
    private static String sourceTextForPojo(
            Pojo pojo, Map<String, String> sourceTextByPackage) {
        if (sourceTextByPackage == null || sourceTextByPackage.isEmpty() || pojo == null) {
            return null;
        }

        String packageName = null;
        if (pojo instanceof AppPojo) {
            packageName = ((AppPojo) pojo).packageName;
        } else if (pojo instanceof ShortcutPojo) {
            packageName = ((ShortcutPojo) pojo).packageName;
        }
        return packageName == null ? null : sourceTextByPackage.get(packageName);
    }

    private static void insert(MutableGraph graph, Pojo pojo, float[] vector) {
        int level = deterministicLevel(pojo.id);
        MutableNode node = new MutableNode(pojo, vector, level);
        int newIndex = graph.nodes.size();
        graph.nodes.add(node);

        if (graph.entryPoint < 0) {
            graph.entryPoint = newIndex;
            graph.maxLevel = level;
            return;
        }

        int entry = graph.entryPoint;
        float entryScore = dot(vector, graph.nodes.get(entry).vector);

        for (int currentLevel = graph.maxLevel; currentLevel > level; currentLevel--) {
            Candidate best = greedyClosest(graph, vector, entry, entryScore, currentLevel);
            entry = best.index;
            entryScore = best.score;
        }

        int connectFrom = Math.min(level, graph.maxLevel);
        for (int currentLevel = connectFrom; currentLevel >= 0; currentLevel--) {
            List<Candidate> candidates = searchLayer(
                    graph,
                    vector,
                    Collections.singletonList(entry),
                    graph.constructionEf,
                    currentLevel);
            candidates.sort((left, right) -> Float.compare(right.score, left.score));

            int links = Math.min(graph.maxConnections, candidates.size());
            for (int i = 0; i < links; i++) {
                int neighbor = candidates.get(i).index;
                if (neighbor == newIndex) continue;
                connectBidirectional(graph, newIndex, neighbor, currentLevel);
            }
            if (!candidates.isEmpty()) {
                entry = candidates.get(0).index;
                entryScore = candidates.get(0).score;
            }
        }

        if (level > graph.maxLevel) {
            graph.entryPoint = newIndex;
            graph.maxLevel = level;
        }
    }

    private static void connectBidirectional(MutableGraph graph,
                                             int left,
                                             int right,
                                             int level) {
        MutableNode leftNode = graph.nodes.get(left);
        MutableNode rightNode = graph.nodes.get(right);
        if (level > leftNode.level || level > rightNode.level) return;

        addUnique(leftNode.neighbors.get(level), right);
        addUnique(rightNode.neighbors.get(level), left);
        trimNeighbors(graph, left, level);
        trimNeighbors(graph, right, level);
    }

    private static void addUnique(List<Integer> neighbors, int value) {
        if (!neighbors.contains(value)) neighbors.add(value);
    }

    private static void trimNeighbors(MutableGraph graph, int nodeIndex, int level) {
        List<Integer> neighbors = graph.nodes.get(nodeIndex).neighbors.get(level);
        if (neighbors.size() <= graph.maxConnections) return;

        // Cache scores once. Comparator-based dot products recalculated the same 384-float
        // similarities many times while trimming every HNSW edge.
        float[] base = graph.nodes.get(nodeIndex).vector;
        List<Candidate> ranked = new ArrayList<>(neighbors.size());
        for (int neighbor : neighbors) {
            ranked.add(new Candidate(
                    neighbor,
                    dot(base, graph.nodes.get(neighbor).vector)));
        }
        ranked.sort((left, right) -> Float.compare(right.score, left.score));

        neighbors.clear();
        int keep = Math.min(graph.maxConnections, ranked.size());
        for (int i = 0; i < keep; i++) {
            neighbors.add(ranked.get(i).index);
        }
    }

    private static Candidate greedyClosest(GraphAccess graph,
                                           float[] query,
                                           int start,
                                           float startScore,
                                           int level) {
        int current = start;
        float currentScore = startScore;
        boolean changed;

        do {
            changed = false;
            for (int neighbor : graph.neighbors(current, level)) {
                float score = dot(query, graph.vector(neighbor));
                if (score > currentScore) {
                    current = neighbor;
                    currentScore = score;
                    changed = true;
                }
            }
        } while (changed);

        return new Candidate(current, currentScore);
    }

    private static List<Candidate> searchLayer(GraphAccess graph,
                                               float[] query,
                                               List<Integer> entryPoints,
                                               int ef,
                                               int level) {
        PriorityQueue<Candidate> candidates = new PriorityQueue<>(
                Math.max(11, ef),
                (left, right) -> Float.compare(right.score, left.score));
        PriorityQueue<Candidate> best = new PriorityQueue<>(
                Math.max(11, ef),
                Comparator.comparingDouble(value -> value.score));
        // Node ids are dense indexes. A boolean array avoids Integer boxing/hash-table traffic
        // on every graph hop, which matters more once vectors grow from 128 to 384 dimensions.
        boolean[] visited = new boolean[graph.size()];

        for (int entry : entryPoints) {
            if (entry < 0 || entry >= graph.size() || visited[entry]) continue;
            visited[entry] = true;
            float score = dot(query, graph.vector(entry));
            Candidate candidate = new Candidate(entry, score);
            candidates.offer(candidate);
            best.offer(candidate);
        }

        while (!candidates.isEmpty()) {
            Candidate current = candidates.poll();
            Candidate worstBest = best.peek();
            if (worstBest != null && best.size() >= ef && current.score < worstBest.score) {
                break;
            }

            for (int neighbor : graph.neighbors(current.index, level)) {
                if (neighbor < 0 || neighbor >= visited.length || visited[neighbor]) continue;
                visited[neighbor] = true;
                float score = dot(query, graph.vector(neighbor));
                Candidate worst = best.peek();

                if (best.size() < ef || worst == null || score > worst.score) {
                    Candidate next = new Candidate(neighbor, score);
                    candidates.offer(next);
                    best.offer(next);
                    if (best.size() > ef) best.poll();
                }
            }
        }

        return new ArrayList<>(best);
    }

    private static int deterministicLevel(String id) {
        int hash = id == null ? 0 : smear(id.hashCode());
        int level = 0;
        while (level < MAX_LEVEL && (hash & 0x3) == 0) {
            level++;
            hash >>>= 2;
        }
        return level;
    }

    private static int smear(int hash) {
        hash ^= (hash >>> 16);
        hash *= 0x7feb352d;
        hash ^= (hash >>> 15);
        hash *= 0x846ca68b;
        hash ^= (hash >>> 16);
        return hash;
    }

    private static float dot(float[] left, float[] right) {
        int count = Math.min(left.length, right.length);
        float sum = 0f;
        int i = 0;

        // Eight-wide unrolling reduces loop/control overhead in the hottest HNSW operation.
        // Vectors are already normalized, so no per-comparison norm work is required.
        for (; i + 7 < count; i += 8) {
            sum += left[i] * right[i]
                    + left[i + 1] * right[i + 1]
                    + left[i + 2] * right[i + 2]
                    + left[i + 3] * right[i + 3]
                    + left[i + 4] * right[i + 4]
                    + left[i + 5] * right[i + 5]
                    + left[i + 6] * right[i + 6]
                    + left[i + 7] * right[i + 7];
        }
        for (; i < count; i++) {
            sum += left[i] * right[i];
        }
        return Math.max(0f, sum);
    }

    private static boolean isZero(float[] vector) {
        for (float value : vector) {
            if (value != 0f) return false;
        }
        return true;
    }

    public static int parseEfSearch(SharedPreferences prefs) {
        int parsed = DEFAULT_EF_SEARCH;
        try {
            parsed = Integer.parseInt(
                    prefs.getString(
                            PREF_HNSW_EF_SEARCH,
                            Integer.toString(DEFAULT_EF_SEARCH)));
        } catch (NumberFormatException | ClassCastException ignored) {
            parsed = DEFAULT_EF_SEARCH;
        }

        // 3.30.160's balanced default was 96. 384D + M16 provides stronger separation and
        // connectivity, so 80 keeps recall high while reducing graph work on every keystroke.
        if (!prefs.getBoolean(PREF_HNSW_TUNING_UPGRADE_DONE, false)) {
            if (parsed == 96) parsed = DEFAULT_EF_SEARCH;
            parsed = clamp(parsed, MIN_EF_SEARCH, MAX_EF_SEARCH);
            prefs.edit()
                    .putString(PREF_HNSW_EF_SEARCH, Integer.toString(parsed))
                    .putBoolean(PREF_HNSW_TUNING_UPGRADE_DONE, true)
                    .apply();
        }

        return clamp(parsed, MIN_EF_SEARCH, MAX_EF_SEARCH);
    }

    public static int parseDimensions(SharedPreferences prefs) {
        int parsed = DEFAULT_DIMENSIONS;
        try {
            parsed = Integer.parseInt(
                    prefs.getString(
                            PREF_EMBEDDING_DIMENSIONS,
                            Integer.toString(DEFAULT_DIMENSIONS)));
        } catch (NumberFormatException | ClassCastException ignored) {
            parsed = DEFAULT_DIMENSIONS;
        }

        // 3.30.160 used 128 by default. Upgrade that legacy low-dimensional setting once so an
        // existing installation actually receives the new semantic representation instead of
        // silently staying on 128 forever. Explicit 256+ choices remain untouched.
        if (!prefs.getBoolean(PREF_DIMENSION_UPGRADE_DONE, false)) {
            if (parsed < MIN_DIMENSIONS) parsed = DEFAULT_DIMENSIONS;
            parsed = clamp(parsed, MIN_DIMENSIONS, MAX_DIMENSIONS);
            prefs.edit()
                    .putString(PREF_EMBEDDING_DIMENSIONS, Integer.toString(parsed))
                    .putBoolean(PREF_DIMENSION_UPGRADE_DONE, true)
                    .apply();
        }

        return clamp(parsed, MIN_DIMENSIONS, MAX_DIMENSIONS);
    }

    static int connectionsForDimensions(int dimensions) {
        if (dimensions >= 384) return 16;
        if (dimensions >= 320) return 15;
        return 14;
    }

    static int constructionEfForDimensions(int dimensions) {
        if (dimensions >= 384) return 80;
        if (dimensions >= 320) return 76;
        return 72;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long nanosToMs(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    public static final class Hit {
        public final Pojo pojo;
        public final float score;

        Hit(Pojo pojo, float score) {
            this.pojo = pojo;
            this.score = score;
        }
    }

    private static final class Candidate {
        final int index;
        final float score;

        Candidate(int index, float score) {
            this.index = index;
            this.score = score;
        }
    }

    private interface GraphAccess {
        int size();
        float[] vector(int index);
        List<Integer> neighbors(int index, int level);
    }

    private static final class MutableGraph implements GraphAccess {
        final int dimensions;
        final int maxConnections;
        final int constructionEf;
        final List<MutableNode> nodes = new ArrayList<>();
        int entryPoint = -1;
        int maxLevel;

        MutableGraph(int dimensions) {
            this.dimensions = dimensions;
            this.maxConnections = connectionsForDimensions(dimensions);
            this.constructionEf = constructionEfForDimensions(dimensions);
        }

        @Override public int size() {
            return nodes.size();
        }

        @Override public float[] vector(int index) {
            return nodes.get(index).vector;
        }

        @Override public List<Integer> neighbors(int index, int level) {
            MutableNode node = nodes.get(index);
            if (level > node.level) return Collections.emptyList();
            return node.neighbors.get(level);
        }

        Snapshot freeze() {
            if (nodes.isEmpty()) return Snapshot.empty();

            List<Node> frozen = new ArrayList<>(nodes.size());
            for (MutableNode mutable : nodes) {
                List<List<Integer>> levels = new ArrayList<>(mutable.neighbors.size());
                for (List<Integer> neighbors : mutable.neighbors) {
                    levels.add(Collections.unmodifiableList(new ArrayList<>(neighbors)));
                }
                frozen.add(new Node(
                        mutable.pojo,
                        mutable.vector,
                        mutable.level,
                        Collections.unmodifiableList(levels)));
            }
            return new Snapshot(
                    dimensions,
                    Collections.unmodifiableList(frozen),
                    entryPoint,
                    maxLevel,
                    maxConnections,
                    constructionEf);
        }
    }

    private static final class MutableNode {
        final Pojo pojo;
        final float[] vector;
        final int level;
        final List<List<Integer>> neighbors;

        MutableNode(Pojo pojo, float[] vector, int level) {
            this.pojo = pojo;
            this.vector = vector;
            this.level = level;
            this.neighbors = new ArrayList<>(level + 1);
            for (int i = 0; i <= level; i++) neighbors.add(new ArrayList<>());
        }
    }

    private static final class Node {
        final Pojo pojo;
        final float[] vector;
        final int level;
        final List<List<Integer>> neighbors;

        Node(Pojo pojo, float[] vector, int level, List<List<Integer>> neighbors) {
            this.pojo = pojo;
            this.vector = vector;
            this.level = level;
            this.neighbors = neighbors;
        }
    }

    private static final class Snapshot implements GraphAccess {
        final int dimensions;
        final List<Node> nodes;
        final int entryPoint;
        final int maxLevel;
        final int maxConnections;
        final int constructionEf;

        Snapshot(int dimensions,
                 List<Node> nodes,
                 int entryPoint,
                 int maxLevel,
                 int maxConnections,
                 int constructionEf) {
            this.dimensions = dimensions;
            this.nodes = nodes;
            this.entryPoint = entryPoint;
            this.maxLevel = maxLevel;
            this.maxConnections = maxConnections;
            this.constructionEf = constructionEf;
        }

        static Snapshot empty() {
            return new Snapshot(0, Collections.emptyList(), -1, 0, 0, 0);
        }

        @Override public int size() {
            return nodes.size();
        }

        @Override public float[] vector(int index) {
            return nodes.get(index).vector;
        }

        @Override public List<Integer> neighbors(int index, int level) {
            Node node = nodes.get(index);
            if (level > node.level) return Collections.emptyList();
            return node.neighbors.get(level);
        }
    }

    // Package-private deterministic helpers for JVM regression tests.
    static List<Hit> buildAndSearchForTest(List<Pojo> pojos,
                                           String query,
                                           int dimensions,
                                           int count,
                                           int efSearch) {
        return buildAndSearchForTest(
                pojos, Collections.emptyMap(), query, dimensions, count, efSearch);
    }

    static List<Hit> buildAndSearchForTest(List<Pojo> pojos,
                                           Map<String, String> extraSemanticTextById,
                                           String query,
                                           int dimensions,
                                           int count,
                                           int efSearch) {
        MutableGraph graph = new MutableGraph(dimensions);
        Set<String> seen = new HashSet<>();
        for (Pojo pojo : pojos) {
            if (pojo == null || pojo.id == null || !seen.add(pojo.id)) continue;
            String extra = extraSemanticTextById == null
                    ? null : extraSemanticTextById.get(pojo.id);
            float[] vector = SemanticEmbeddingScorer.prepareCandidate(pojo, dimensions, extra);
            if (!isZero(vector)) insert(graph, pojo, vector);
        }
        Snapshot snapshot = graph.freeze();
        if (snapshot.nodes.isEmpty()) return Collections.emptyList();

        float[] queryVector = SemanticEmbeddingScorer.prepareQuery(query, dimensions);
        int entry = snapshot.entryPoint;
        float entryScore = dot(queryVector, snapshot.nodes.get(entry).vector);
        for (int level = snapshot.maxLevel; level > 0; level--) {
            Candidate improved = greedyClosest(snapshot, queryVector, entry, entryScore, level);
            entry = improved.index;
            entryScore = improved.score;
        }

        List<Candidate> nearest = searchLayer(
                snapshot, queryVector, Collections.singletonList(entry),
                Math.max(count, efSearch), 0);
        nearest.sort((left, right) -> Float.compare(right.score, left.score));
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < nearest.size() && hits.size() < count; i++) {
            Candidate candidate = nearest.get(i);
            hits.add(new Hit(snapshot.nodes.get(candidate.index).pojo, candidate.score));
        }
        return hits;
    }
}
