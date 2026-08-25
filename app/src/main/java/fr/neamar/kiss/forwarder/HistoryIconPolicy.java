package fr.neamar.kiss.forwarder;

/** Pure decision policy for icons copied from an adapter row into a custom history renderer. */
final class HistoryIconPolicy {
    private HistoryIconPolicy() {
    }

    static boolean isRenderable(boolean drawablePresent, int drawableAlpha,
                                boolean transparentColorPlaceholder) {
        return drawablePresent && drawableAlpha > 0 && !transparentColorPlaceholder;
    }
}
