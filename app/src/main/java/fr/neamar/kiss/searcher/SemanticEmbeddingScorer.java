package fr.neamar.kiss.searcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.PojoWithTags;

/**
 * Small deterministic embedding scorer for launcher search.
 *
 * This intentionally has no neural-runtime dependency and no network requirement. It combines
 * token, character n-gram, phrase and concept features into a fixed-size signed hash embedding,
 * then uses cosine similarity. The model name exposed in Settings is "Smart S Mini Embedding v1".
 */
public final class SemanticEmbeddingScorer {
    public static final String MODEL_ID = "smart-s-mini-v1";
    public static final String MODEL_NAME = "Smart S Mini Embedding v1";

    private static final Map<String, List<String>> CONCEPTS = new HashMap<>();

    static {
        addConcept("notes", "note", "notes", "markdown", "obsidian", "notion", "keep", "markor", "memo", "writing");
        addConcept("bible", "bible", "scripture", "scriptures", "jw", "jwlibrary", "jw library", "watchtower", "wol", "worship", "prayer", "meeting", "ministry");
        addConcept("message", "message", "messages", "sms", "mms", "chat", "telegram", "whatsapp", "messenger", "signal");
        addConcept("money", "money", "bank", "banking", "finance", "wallet", "pay", "payment", "fnb", "capitec", "africanbank", "african bank");
        addConcept("browser", "browser", "web", "internet", "www", "chrome", "firefox", "edge", "opera", "via", "comet");
        addConcept("navigation", "navigation", "navigate", "maps", "map", "directions", "gps", "waze", "osmand", "sygic");
        addConcept("music", "music", "audio", "song", "songs", "spotify", "youtube music", "radio");
        addConcept("video", "video", "videos", "movie", "movies", "series", "stream", "youtube", "netflix", "plex");
        addConcept("camera", "camera", "photo", "photos", "picture", "pictures", "scan", "scanner", "gcam");
        addConcept("files", "file", "files", "folder", "folders", "storage", "explorer", "solid explorer", "xplore", "downloads");
        addConcept("settings", "settings", "system", "android", "configuration", "preferences", "permission", "permissions");
        addConcept("battery", "battery", "power", "charging", "charge", "accubattery", "saver");
        addConcept("social", "social", "instagram", "facebook", "twitter", "x", "threads", "reddit", "quora", "tiktok");
        addConcept("ai", "ai", "artificial intelligence", "chatgpt", "gemini", "claude", "perplexity", "grok", "mistral", "deepseek", "deep seek");
        addConcept("remote", "remote", "tv", "television", "blu ray", "bluray", "infrared", "ir", "chromecast");
        addConcept("work", "work", "office", "outlook", "email", "mail", "teams", "documents", "word", "excel");
    }

    private SemanticEmbeddingScorer() {
    }

    private static void addConcept(String concept, String... words) {
        CONCEPTS.put(concept, Arrays.asList(words));
    }

    public static float score(String query, Pojo pojo, int dimensions) {
        if (query == null || query.trim().isEmpty() || pojo == null) return 0f;
        return scorePrepared(prepareQuery(query, dimensions), pojo);
    }

    /** Prepare the immutable query side once for a complete search generation. */
    public static float[] prepareQuery(String query, int dimensions) {
        return embed(query, dimensions);
    }

    /** Score a candidate without rebuilding the query vector for every indexed record. */
    public static float scorePrepared(float[] preparedQuery, Pojo pojo) {
        if (preparedQuery == null || preparedQuery.length == 0 || pojo == null) return 0f;
        StringBuilder candidate = new StringBuilder();
        if (pojo.getName() != null) candidate.append(pojo.getName()).append(' ');
        if (pojo instanceof PojoWithTags) {
            String tags = ((PojoWithTags) pojo).getTags();
            if (tags != null && !tags.isEmpty()) candidate.append(tags).append(' ');
        }
        if (pojo instanceof AppPojo) {
            candidate.append(((AppPojo) pojo).packageName);
        }
        return cosine(preparedQuery, embed(candidate.toString(), preparedQuery.length));
    }

    private static float[] embed(String text, int dimensions) {
        int dims = Math.max(32, Math.min(512, dimensions));
        float[] vector = new float[dims];
        String normalized = normalize(text);
        if (normalized.isEmpty()) return vector;

        List<String> tokens = new ArrayList<>(Arrays.asList(normalized.split("\\s+")));
        Set<String> conceptTokens = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : CONCEPTS.entrySet()) {
            for (String rawAlias : entry.getValue()) {
                String alias = normalize(rawAlias);
                if (alias.isEmpty()) continue;
                if (alias.indexOf(' ') >= 0) {
                    if (containsPhrase(normalized, alias)) conceptTokens.add("concept:" + entry.getKey());
                } else if (tokens.contains(alias)) {
                    conceptTokens.add("concept:" + entry.getKey());
                }
            }
        }

        for (String token : tokens) {
            addFeature(vector, "tok:" + token, 1.6f);
            if (token.length() >= 3) {
                String padded = "^" + token + "$";
                for (int i = 0; i <= padded.length() - 3; i++) {
                    addFeature(vector, "tri:" + padded.substring(i, i + 3), 0.45f);
                }
            }
        }
        for (String concept : conceptTokens) addFeature(vector, concept, 2.4f);

        for (int i = 0; i + 1 < tokens.size(); i++) {
            addFeature(vector, "bi:" + tokens.get(i) + "_" + tokens.get(i + 1), 1.2f);
        }
        // Whole normalized phrase gives short multi-word queries a stable shared feature without
        // overpowering token/concept similarity.
        if (tokens.size() >= 2 && tokens.size() <= 6) addFeature(vector, "phrase:" + normalized, 1.35f);

        normalizeVector(vector);
        return vector;
    }

    private static boolean containsPhrase(String normalizedText, String normalizedPhrase) {
        return (" " + normalizedText + " ").contains(" " + normalizedPhrase + " ");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private static void addFeature(float[] vector, String feature, float weight) {
        int hash = feature.hashCode();
        int index = (hash & 0x7fffffff) % vector.length;
        float sign = ((hash >>> 30) & 1) == 0 ? 1f : -1f;
        vector[index] += sign * weight;
    }

    private static void normalizeVector(float[] vector) {
        double sum = 0d;
        for (float v : vector) sum += v * v;
        if (sum <= 0d) return;
        float inv = (float) (1d / Math.sqrt(sum));
        for (int i = 0; i < vector.length; i++) vector[i] *= inv;
    }

    private static float cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        float dot = 0f;
        for (int i = 0; i < n; i++) dot += a[i] * b[i];
        return Math.max(0f, dot);
    }
}
