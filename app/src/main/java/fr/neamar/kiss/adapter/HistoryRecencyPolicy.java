package fr.neamar.kiss.adapter;

/** Pure ordering rule for oldest-to-newest launcher history. */
final class HistoryRecencyPolicy {
    private HistoryRecencyPolicy() { }

    static boolean shouldMoveToNewest(int selectedPosition, int itemCount) {
        return itemCount > 1 && selectedPosition >= 0 && selectedPosition < itemCount - 1;
    }
}
