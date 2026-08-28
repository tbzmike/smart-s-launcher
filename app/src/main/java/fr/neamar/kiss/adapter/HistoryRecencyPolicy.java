package fr.neamar.kiss.adapter;

import java.util.List;

/** Pure ordering rule for oldest-to-newest launcher history. */
final class HistoryRecencyPolicy {
    private HistoryRecencyPolicy() { }

    static boolean shouldMoveToNewest(int selectedPosition, int itemCount) {
        return itemCount > 1 && selectedPosition >= 0 && selectedPosition < itemCount - 1;
    }

    static <T> boolean moveSelectedToNewest(List<T> items, int selectedPosition) {
        if (items == null || !shouldMoveToNewest(selectedPosition, items.size())) return false;
        T selected = items.remove(selectedPosition);
        items.add(selected);
        return true;
    }
}
