package fr.neamar.kiss.activitylauncher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ActivityLauncherSearch {
    private ActivityLauncherSearch() { }

    @NonNull
    static List<String> terms(@Nullable String raw) {
        Set<String> unique = new LinkedHashSet<>();
        if (raw != null) {
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                for (String part : normalized.split("\\s+")) {
                    if (!part.isEmpty()) unique.add(part);
                }
            }
        }
        return new ArrayList<>(unique);
    }

    static boolean matchesAll(@NonNull List<String> terms, @Nullable String... values) {
        if (terms.isEmpty()) return true;
        StringBuilder haystack = new StringBuilder();
        if (values != null) {
            for (String value : values) {
                if (value != null) haystack.append(value).append('\n');
            }
        }
        String lower = haystack.toString().toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!lower.contains(term)) return false;
        }
        return true;
    }
}
