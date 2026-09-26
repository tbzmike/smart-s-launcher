package fr.neamar.kiss.notification;

import java.util.Locale;

/**
 * Presentation-level duplicate guard for different Android notification slots that render as the
 * same visible event. Stable notification IDs remain authoritative for routing and persistence;
 * this helper is only used to avoid showing two tiles for one near-simultaneous visible event.
 */
public final class NotificationDisplayDeduplicator {
    private static final long DUPLICATE_WINDOW_MS = 90_000L;

    private NotificationDisplayDeduplicator() {
    }

    public static boolean isNearDuplicate(String packageA, String groupA,
                                          String titleA, String bodyA, long postTimeA,
                                          String packageB, String groupB,
                                          String titleB, String bodyB, long postTimeB) {
        if (!same(packageA, packageB) || !same(groupA, groupB)) return false;
        if (postTimeA <= 0L || postTimeB <= 0L) return false;
        if (Math.abs(postTimeA - postTimeB) > DUPLICATE_WINDOW_MS) return false;

        String normalizedTitleA = normalize(titleA);
        String normalizedTitleB = normalize(titleB);
        String normalizedBodyA = normalize(bodyA);
        String normalizedBodyB = normalize(bodyB);

        // Empty/generic notifications need their Android identity because there is no content-safe
        // presentation fingerprint with which to distinguish two legitimate events.
        if (normalizedTitleA.isEmpty() && normalizedBodyA.isEmpty()) return false;

        return normalizedTitleA.equals(normalizedTitleB)
                && normalizedBodyA.equals(normalizedBodyB);
    }

    private static boolean same(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
