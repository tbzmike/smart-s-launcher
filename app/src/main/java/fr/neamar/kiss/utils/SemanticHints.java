package fr.neamar.kiss.utils;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tiny, allocation-conscious semantic fallback for common launcher concepts.
 * This deliberately uses no model and no network access. A future downloadable
 * embedding model can plug in above this layer without changing providers.
 */
public final class SemanticHints {
    private SemanticHints() {
    }

    @NonNull
    public static List<String> expand(@NonNull String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        Set<String> hints = new LinkedHashSet<>();
        if (normalized.length() < 3) return new ArrayList<>();

        addIfContains(normalized, hints, "prayer", "notes", "jw library", "bible");
        addIfContains(normalized, hints, "scripture", "jw library", "bible", "watchtower");
        addIfContains(normalized, hints, "meeting", "zoom", "teams", "calendar");
        addIfContains(normalized, hints, "money", "bank", "wallet", "pay");
        addIfContains(normalized, hints, "banking", "bank", "wallet", "pay");
        addIfContains(normalized, hints, "music", "spotify", "youtube music", "audio");
        addIfContains(normalized, hints, "video", "youtube", "netflix", "media");
        addIfContains(normalized, hints, "message", "messages", "whatsapp", "sms");
        addIfContains(normalized, hints, "email", "gmail", "outlook", "mail");
        addIfContains(normalized, hints, "browser", "chrome", "firefox", "internet");
        addIfContains(normalized, hints, "photo", "gallery", "photos", "camera");
        addIfContains(normalized, hints, "navigate", "maps", "navigation");
        addIfContains(normalized, hints, "weather", "forecast", "weather");
        addIfContains(normalized, hints, "alarm", "clock", "alarm");

        return new ArrayList<>(hints);
    }

    private static void addIfContains(String query, Set<String> output, String concept, String... values) {
        if (!query.contains(concept)) return;
        for (String value : values) output.add(value);
    }
}
