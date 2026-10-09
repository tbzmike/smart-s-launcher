package fr.neamar.kiss.ui;

/** Pure positioning arithmetic for chronological ListView and RecyclerView scrubbers. */
public final class DateScrollPositionMath {
    private DateScrollPositionMath() {}

    public static int position(float fraction, int total) {
        if (total <= 1) return 0;
        if (Float.isNaN(fraction)) fraction = 0f;
        return Math.max(0, Math.min(total - 1,
                Math.round(Math.max(0f, Math.min(1f, fraction)) * (total - 1))));
    }

    public static float fraction(int firstVisible, int visible, int total) {
        if (total <= 1) return 0f;
        int scrollable = Math.max(1, total - Math.max(1, visible));
        return Math.max(0f, Math.min(1f, firstVisible / (float) scrollable));
    }
}
