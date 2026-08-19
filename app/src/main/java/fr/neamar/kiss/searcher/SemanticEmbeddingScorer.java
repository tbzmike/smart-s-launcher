package fr.neamar.kiss.searcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.PojoWithTags;

/**
 * Small deterministic embedding scorer for launcher search.
 *
 * This intentionally has no neural-runtime dependency and no network requirement. It combines
 * token, character n-gram and concept features into a fixed-size signed hash embedding, then uses
 * cosine similarity. The model name exposed in Settings is "Smart S Mini Embedding v1".
 */
public final class SemanticEmbeddingScorer {
    public static final String MODEL_ID = "smart-s-mini-v1";
    public static final String MODEL_NAME = "Smart S Mini Embedding v1";

    private static final Map<String, List<String>> CONCEPTS = new HashMap<>();

    static {
        addConcept("notes", "note", "notes", "markdown", "obsidian", "notion", "keep", "markor", "memo", "writing");
        addConcept("bible", "bible", "scripture", "scriptures", "jw", "jwlibrary", "watchtower", "wol", "worship", "prayer", "meeting", "ministry");
        addConcept("message", "message", "messages", "sms", "mms", "chat", "telegram", "whatsapp", "messenger", "signal");
        addConcept("money", "money", "bank", "banking", "finance", "wallet", "pay", "payment", "fnb", "capitec", "africanbank");
        addConcept("browser", "browser", "web", "internet", "www", "chrome", "firefox", "edge", "opera", "via", "comet");
        addConcept("navigation", "navigation", "navigate", "maps", "map", "directions", "gps", "waze", "osmand", "sygic");
        addConcept("music", "music", "audio", "song", "songs", "spotify", "youtube music", "radio");
        addConcept("video", "video", "videos", "movie", "movies", "series", "stream", "youtube", "netflix", "plex");
        addConcept("camera", "camera", "photo", "photos", "picture", "pictures", "scan", "scanner", "gcam");
        addConcept("files", "file", "files", "folder", "folders", "storage", "explorer", "solid explorer", "xplore", "downloads");
        addConcept("settings", "settings", "system", "android", "configuration", "preferences", "permission", "permissions");
        addConcept("battery", "battery", "power", "charging", "charge", "accubattery", "saver");
        addConcept("social", "social", "instagram", "facebook", "twitter", "x", "threads", "reddit", "quora", "tiktok");
        addConcept("ai", "ai", "artificial intelligence", "chatgpt", "gemini", "claude", "perplexity", "grok", "mistral", "deepseek");
        addConcept("remote", "remote", "tv", "television", "blu-ray", "bluray", "infrared", "ir", "chromecast");
        addConcept("work", "work", "office", "outlook", "email", "mail", "teams", "documents", "word", "excel");
    }

    private SemanticEmbeddingScorer() {
    }

    private static void addConcept(String concept, String... words) {
        CONCEPTS.put(concept, Arrays.asList(words));
    }

    public static float score(String query, Pojo pojo, int dimensions) {
        if (query == null || query.trim().isEmpty() || pojo == null) return 0f;
        String candidate = pojo.getName();
        if (pojo instanceof PojoWithTags) {
            String tags = ((PojoWithTags) pojo).getTags();
            if (tags != null && !tags.isEmpty()) candidate += " " + tags;
        }
        if (pojo instanceof AppPojo) {
            candidate += " " + ((AppPojo) pojo).packageName;
        }
        return cosine(embed(query, dimensions), embed(candidate, dimensions));
    }

    private static float[] embed(String text, int dimensions) {
        int dims = Math.max(32, Math.min(512, dimensions));
        float[] vector = new float[dims];
        String normalized = normalize(text);
        if (normalized.isEmpty()) return vector;

        List<String> tokens = new ArrayList<>(Arrays.asList(normalized.split("\\s+")));
        List<String> conceptTokens = new ArrayList<>();
        for (String token : tokens) {
            for (Map.Entry<String, List<String>> entry : CONCEPTS.entrySet()) {
                if (entry.getValue().contains(token)) conceptTokens.add("concept:" + entry.getKey());
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

        // Phrase features retain some order without needing a tokenizer/model runtime.
        for (int i = 0; i + 1 < tokens.size(); i++) {
            addFeature(vector, "bi:" + tokens.get(i) + "_" + tokens.get(i + 1), 1.2f);
        }
        normalizeVector(vector);
        return vector;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
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
