package fr.neamar.kiss.notification;

import java.util.Locale;

/** Visible identity check used only when Android refreshes an existing notification key. */
final class NotificationEventIdentity {
    private NotificationEventIdentity() { }

    static boolean hasSameVisibleContent(String savedTitle, String savedBody,
                                         String currentTitle, String currentBody) {
        String expectedTitle = normalize(savedTitle);
        String expectedBody = normalize(savedBody);
        if (expectedTitle.isEmpty() && expectedBody.isEmpty()) return false;
        return expectedTitle.equals(normalize(currentTitle))
                && expectedBody.equals(normalize(currentBody));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String source = value.trim().toLowerCase(Locale.ROOT);
        if (source.isEmpty()) return "";

        StringBuilder normalized = new StringBuilder(source.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < source.length();) {
            int codePoint = source.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) normalized.append(' ');
            normalized.appendCodePoint(codePoint);
            pendingSpace = false;
        }
        return normalized.toString();
    }
}
