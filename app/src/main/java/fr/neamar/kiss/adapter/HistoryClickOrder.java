package fr.neamar.kiss.adapter;

import java.util.List;

/**
 * Maintains the launcher history timeline contract for an explicitly selected item.
 * The selected item becomes the final (newest) row and older selections naturally move
 * upward as later items are selected.
 */
final class HistoryClickOrder {
    private HistoryClickOrder() { }

    static <T> boolean moveToEnd(List<T> items, T selected) {
        if (items == null || selected == null || items.isEmpty()) return false;

        int index = items.indexOf(selected);
        if (index < 0 || index == items.size() - 1) return false;

        items.remove(index);
        items.add(selected);
        return true;
    }
}
