package fr.neamar.kiss.utils;

import java.util.Locale;
import java.util.Map;

/** Name fallback only for known IceBox publisher shortcuts with a unique app match. */
public final class IceBoxFallbackTargetPolicy {
    private IceBoxFallbackTargetPolicy() { }

    public static String uniqueExactPackage(String requestedLabel, String publisherPackage,
                                            Map<String, String> catalog) {
        if (requestedLabel == null || catalog == null) return null;
        String wanted = requestedLabel.trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) return null;
        String matched = null;
        for (Map.Entry<String, String> entry : catalog.entrySet()) {
            String pkg = entry.getKey();
            String name = entry.getValue();
            if (pkg == null || name == null || pkg.equals(publisherPackage)) continue;
            if (!wanted.equals(name.trim().toLowerCase(Locale.ROOT))) continue;
            if (matched != null && !matched.equals(pkg)) return null;
            matched = pkg;
        }
        return matched;
    }
}
