package fr.neamar.kiss.adapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Keeps a refresh from shrinking an already-visible History window merely because a provider
 * temporarily failed to resolve one of its rows.
 *
 * Explicit user deletion is unaffected: the deleted row is already absent from the adapter before
 * a later refresh reaches this policy. New authoritative rows and their new order are kept; only
 * rows that vanished during the refresh are reinserted at their previous approximate position.
 */
final class HistoryRefreshPreserver {
    private HistoryRefreshPreserver() { }

    static <T> List<T> preserveMissing(List<T> existing,
                                       List<T> refreshed,
                                       Function<T, String> keyProvider) {
        if (existing == null || existing.isEmpty()) {
            return refreshed == null ? new ArrayList<>() : new ArrayList<>(refreshed);
        }
        if (refreshed == null || refreshed.isEmpty()) {
            return new ArrayList<>(existing);
        }

        List<T> merged = new ArrayList<>(refreshed);
        Set<String> present = new HashSet<>(Math.max(16, refreshed.size() * 2));
        for (T item : refreshed) {
            String key = item == null ? null : keyProvider.apply(item);
            if (key != null) present.add(key);
        }

        // Insert from newest/highest old position toward the start so earlier insertions do not
        // disturb the target index for later missing rows.
        for (int i = existing.size() - 1; i >= 0; i--) {
            T item = existing.get(i);
            if (item == null) continue;
            String key = keyProvider.apply(item);
            if (key == null || present.contains(key)) continue;
            merged.add(Math.min(i, merged.size()), item);
            present.add(key);
        }
        return merged;
    }
}
