package fr.neamar.kiss.notification;

import java.util.Locale;

/** Pure classification logic shared by media notification controls and unit tests. */
public final class MediaControlClassifier {
    public enum Kind { PREVIOUS, PLAY_PAUSE, NEXT, OTHER }

    private MediaControlClassifier() {}

    public static Kind classify(CharSequence title) {
        if (title == null) return Kind.OTHER;
        String value = title.toString().trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return Kind.OTHER;
        if (containsAny(value, "previous", "prev", "back", "rewind")) return Kind.PREVIOUS;
        if (containsAny(value, "next", "skip forward")) return Kind.NEXT;
        if (containsAny(value, "play", "pause", "resume")) return Kind.PLAY_PAUSE;
        return Kind.OTHER;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
}
