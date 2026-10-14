package fr.neamar.kiss.forwarder;

/** Eligible independent launcher shortcuts for the Pixel prediction bar. */
final class PixelShortcutPredictionPolicy {
    private PixelShortcutPredictionPolicy() {}
    static boolean isShortcutHistoryId(String id) {
        return id != null && (id.startsWith("shortcut://")
                || id.startsWith("setting://")
                || id.startsWith("feature://"));
    }
}
