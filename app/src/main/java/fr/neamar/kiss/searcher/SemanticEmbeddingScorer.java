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
import fr.neamar.kiss.pojo.ShortcutPojo;

/**
 * Lightweight launcher semantic embedding used by the HNSW index.
 *
 * <p>Version 2 keeps the no-network/no-runtime design, but substantially widens the concept
 * vocabulary, adds stem and package/activity features, and exposes candidate preparation so
 * vectors are generated once during indexing instead of once per query.</p>
 */
public final class SemanticEmbeddingScorer {
    // Keep the persisted ID compatible with existing installations while upgrading its internals.
    public static final String MODEL_ID = "smart-s-mini-v1";
    public static final String MODEL_NAME = "Smart S Semantic v2 + HNSW";

    private static final Map<String, List<String>> CONCEPTS = new HashMap<>();

    static {
        addConcept("notes", "note", "notes", "memo", "memos", "writing", "journal",
                "markdown", "obsidian", "notion", "keep", "markor", "evernote", "onenote");
        addConcept("bible", "bible", "scripture", "scriptures", "jw", "jwlibrary", "jw library",
                "watchtower", "wol", "worship", "prayer", "meeting", "ministry", "kingdom hall");
        addConcept("message", "message", "messages", "messaging", "sms", "mms", "chat", "chats",
                "telegram", "whatsapp", "messenger", "signal", "wechat");
        addConcept("money", "money", "bank", "banking", "finance", "wallet", "pay", "payment",
                "payments", "transfer", "send money", "eft", "fnb", "capitec", "africanbank",
                "african bank", "nedbank", "absa", "standard bank", "paypal");
        addConcept("browser", "browser", "web", "internet", "www", "chrome", "firefox", "edge",
                "opera", "brave", "via", "comet");
        addConcept("navigation", "navigation", "navigate", "map", "maps", "directions", "gps",
                "route", "routes", "waze", "osmand", "sygic", "google maps");
        addConcept("music", "music", "audio", "song", "songs", "playlist", "spotify",
                "youtube music", "radio", "deezer", "tidal", "audiomack");
        addConcept("video", "video", "videos", "movie", "movies", "series", "stream", "streaming",
                "youtube", "netflix", "plex", "disney", "prime video", "showmax");
        addConcept("camera", "camera", "photo", "photos", "picture", "pictures", "scan", "scanner",
                "gcam", "lens", "gallery", "snapseed");
        addConcept("files", "file", "files", "folder", "folders", "storage", "explorer", "manager",
                "solid explorer", "xplore", "downloads", "documents");
        addConcept("settings", "settings", "system", "android", "configuration", "preferences",
                "permission", "permissions", "developer options");
        addConcept("battery", "battery", "power", "charging", "charge", "accubattery", "saver",
                "battery saver");
        addConcept("social", "social", "instagram", "facebook", "twitter", "x", "threads",
                "reddit", "quora", "tiktok", "linkedin");
        addConcept("ai", "ai", "artificial intelligence", "chatgpt", "openai", "gemini", "claude",
                "perplexity", "grok", "mistral", "deepseek", "deep seek", "copilot");
        addConcept("remote", "remote", "tv", "television", "blu ray", "bluray", "infrared", "ir",
                "chromecast", "roku", "smartthings");
        addConcept("work", "work", "office", "outlook", "teams", "documents", "word", "excel",
                "powerpoint", "sheets", "docs", "slack");
        addConcept("email", "email", "emails", "mail", "gmail", "outlook", "protonmail",
                "proton mail", "yahoo mail");
        addConcept("calendar", "calendar", "schedule", "appointment", "appointments", "event",
                "events", "agenda", "reminder", "reminders");
        addConcept("weather", "weather", "forecast", "temperature", "rain", "storm", "wind");
        addConcept("clock", "clock", "alarm", "alarms", "timer", "stopwatch", "time");
        addConcept("calculator", "calculator", "calculate", "math", "maths", "arithmetic");
        addConcept("shopping", "shopping", "shop", "store", "buy", "purchase", "takealot",
                "amazon", "temu", "shein", "ebay");
        addConcept("food", "food", "meal", "restaurant", "delivery", "takeaway", "uber eats",
                "ubereats", "mr d", "mrd", "checkers sixty60", "sixty60");
        addConcept("transport", "transport", "ride", "taxi", "uber", "bolt", "bus", "train",
                "gautrain", "trip");
        addConcept("health", "health", "medical", "medicine", "doctor", "hospital", "fitness",
                "exercise", "workout", "steps");
        addConcept("phone", "phone", "call", "calls", "dial", "dialer", "telephone");
        addConcept("contacts", "contact", "contacts", "people", "address book");
        addConcept("security", "security", "password", "passwords", "authenticator", "vpn",
                "antivirus", "privacy", "lock");
        addConcept("cloud", "cloud", "backup", "drive", "dropbox", "onedrive", "google drive",
                "sync");
        addConcept("news", "news", "headlines", "article", "articles", "feed", "feeds");
    }

    private SemanticEmbeddingScorer() { }

    private static void addConcept(String concept, String... words) {
        CONCEPTS.put(concept, Arrays.asList(words));
    }

    public static float score(String query, Pojo pojo, int dimensions) {
        if (query == null || query.trim().isEmpty() || pojo == null) return 0f;
        return scorePrepared(prepareQuery(query, dimensions), pojo);
    }

    /** Prepare the immutable query side once for a complete search generation. */
    public static float[] prepareQuery(String query, int dimensions) {
        return embed(query, dimensions, true);
    }

    /** Prepare a candidate once for HNSW indexing. */
    public static float[] prepareCandidate(Pojo pojo, int dimensions) {
        if (pojo == null) return new float[Math.max(32, Math.min(512, dimensions))];
        return embed(candidateText(pojo), dimensions, false);
    }

    /** Score a candidate without rebuilding the query vector. Kept for lexical-only reranking. */
    public static float scorePrepared(float[] preparedQuery, Pojo pojo) {
        if (preparedQuery == null || preparedQuery.length == 0 || pojo == null) return 0f;
        return cosine(preparedQuery, prepareCandidate(pojo, preparedQuery.length));
    }

    private static String candidateText(Pojo pojo) {
        StringBuilder candidate = new StringBuilder(96);
        if (pojo.getName() != null) candidate.append(pojo.getName()).append(' ');

        if (pojo instanceof PojoWithTags) {
            String tags = ((PojoWithTags) pojo).getTags();
            if (tags != null && !tags.isEmpty()) candidate.append(tags).append(' ');
        }

        if (pojo instanceof AppPojo) {
            AppPojo app = (AppPojo) pojo;
            if (app.packageName != null) candidate.append(app.packageName).append(' ');
            if (app.activityName != null) candidate.append(app.activityName).append(' ');
        } else if (pojo instanceof ShortcutPojo) {
            ShortcutPojo shortcut = (ShortcutPojo) pojo;
            if (shortcut.packageName != null) candidate.append(shortcut.packageName).append(' ');
        }

        return candidate.toString();
    }

    private static float[] embed(String text, int dimensions, boolean querySide) {
        int dims = Math.max(32, Math.min(512, dimensions));
        float[] vector = new float[dims];
        String normalized = normalize(text);
        if (normalized.isEmpty()) return vector;

        List<String> tokens = tokenize(normalized);
        Set<String> conceptTokens = detectConcepts(normalized, tokens);

        for (String token : tokens) {
            if (token.isEmpty()) continue;
            addFeature(vector, "tok:" + token, 1.65f);

            String stem = lightStem(token);
            if (!stem.equals(token) && stem.length() >= 3) {
                addFeature(vector, "stem:" + stem, 0.90f);
            }

            if (token.length() >= 3) {
                String padded = "^" + token + "$";
                for (int i = 0; i <= padded.length() - 3; i++) {
                    addFeature(vector, "tri:" + padded.substring(i, i + 3), 0.34f);
                }
            }
        }

        // Concept features carry most of the cross-word semantic signal. Give query-side concepts a
        // slight lift so generic intents such as "send money" can find a brand-labelled banking app.
        float conceptWeight = querySide ? 3.15f : 2.85f;
        for (String concept : conceptTokens) addFeature(vector, concept, conceptWeight);

        for (int i = 0; i + 1 < tokens.size(); i++) {
            addFeature(vector, "bi:" + tokens.get(i) + "_" + tokens.get(i + 1), 1.10f);
        }

        if (tokens.size() >= 2 && tokens.size() <= 7) {
            addFeature(vector, "phrase:" + normalized, 1.10f);
        }

        normalizeVector(vector);
        return vector;
    }

    private static Set<String> detectConcepts(String normalized, List<String> tokens) {
        Set<String> tokenSet = new HashSet<>(tokens);
        Set<String> concepts = new HashSet<>();

        for (Map.Entry<String, List<String>> entry : CONCEPTS.entrySet()) {
            for (String rawAlias : entry.getValue()) {
                String alias = normalize(rawAlias);
                if (alias.isEmpty()) continue;

                boolean match = alias.indexOf(' ') >= 0
                        ? containsPhrase(normalized, alias)
                        : tokenSet.contains(alias);
                if (match) {
                    concepts.add("concept:" + entry.getKey());
                    break;
                }
            }
        }
        return concepts;
    }

    private static boolean containsPhrase(String normalizedText, String normalizedPhrase) {
        return (" " + normalizedText + " ").contains(" " + normalizedPhrase + " ");
    }

    private static List<String> tokenize(String normalized) {
        List<String> tokens = new ArrayList<>();
        int start = 0;
        int length = normalized.length();
        for (int i = 0; i <= length; i++) {
            if (i == length || normalized.charAt(i) == ' ') {
                if (i > start) tokens.add(normalized.substring(start, i));
                start = i + 1;
            }
        }
        return tokens;
    }

    /**
     * Tiny morphology normalizer. This is deliberately conservative so app/brand names are not
     * damaged, while common forms such as messages/message and payments/payment share a feature.
     */
    private static String lightStem(String token) {
        int length = token.length();
        if (length > 5 && token.endsWith("ing")) return token.substring(0, length - 3);
        if (length > 4 && token.endsWith("ies")) return token.substring(0, length - 3) + "y";
        if (length > 4 && token.endsWith("ed")) return token.substring(0, length - 2);
        if (length > 4 && token.endsWith("es")) return token.substring(0, length - 2);
        if (length > 3 && token.endsWith("s")) return token.substring(0, length - 1);
        return token;
    }

    /**
     * Allocation-light Unicode normalization for the hot query/index path. Punctuation and package
     * separators become one space; letters and numbers are retained.
     */
    private static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean previousSpace = true;

        for (int offset = 0; offset < lower.length();) {
            int codePoint = lower.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (Character.isLetterOrDigit(codePoint)) {
                out.appendCodePoint(codePoint);
                previousSpace = false;
            } else if (!previousSpace) {
                out.append(' ');
                previousSpace = true;
            }
        }

        int length = out.length();
        if (length > 0 && out.charAt(length - 1) == ' ') out.setLength(length - 1);
        return out.toString();
    }

    private static void addFeature(float[] vector, String feature, float weight) {
        int hash = smear(feature.hashCode());
        int index = (hash & 0x7fffffff) % vector.length;
        float sign = ((hash >>> 30) & 1) == 0 ? 1f : -1f;
        vector[index] += sign * weight;
    }

    private static int smear(int hash) {
        hash ^= (hash >>> 16);
        hash *= 0x7feb352d;
        hash ^= (hash >>> 15);
        hash *= 0x846ca68b;
        hash ^= (hash >>> 16);
        return hash;
    }

    private static void normalizeVector(float[] vector) {
        double sum = 0d;
        for (float value : vector) sum += value * value;
        if (sum <= 0d) return;
        float inverse = (float) (1d / Math.sqrt(sum));
        for (int i = 0; i < vector.length; i++) vector[i] *= inverse;
    }

    private static float cosine(float[] left, float[] right) {
        int count = Math.min(left.length, right.length);
        float dot = 0f;
        for (int i = 0; i < count; i++) dot += left[i] * right[i];
        return Math.max(0f, dot);
    }
}
