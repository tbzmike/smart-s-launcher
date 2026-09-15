package fr.neamar.kiss.forwarder;

/** The complete set of history renderers supported by the current launcher version. */
final class HistoryLayoutMode {
    static final String VERTICAL = "vertical";
    static final String VERTICAL_CARDS = "vertical_cards";
    static final String WHEEL_3D = "wheel_3d";

    private HistoryLayoutMode() {}

    static String normalize(String value) {
        if (VERTICAL_CARDS.equals(value) || WHEEL_3D.equals(value)) return value;
        return VERTICAL;
    }

    static boolean isSupported(String value) {
        return VERTICAL.equals(value) || VERTICAL_CARDS.equals(value) || WHEEL_3D.equals(value);
    }
}
