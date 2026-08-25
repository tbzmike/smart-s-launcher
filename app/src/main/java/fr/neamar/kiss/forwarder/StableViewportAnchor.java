package fr.neamar.kiss.forwarder;

import java.util.List;

/** Resolves a rebuilt card by stable result identity instead of its changeable list index. */
final class StableViewportAnchor {
    private StableViewportAnchor() {
    }

    static int resolveIndex(String stableId, int legacyIndex, List<String> rebuiltIds) {
        if (stableId != null && !stableId.isEmpty()) {
            for (int i = 0; i < rebuiltIds.size(); i++) {
                if (stableId.equals(rebuiltIds.get(i))) return i;
            }
            // Never reuse the old numeric index for a missing identity. History re-ranking can
            // make that index point to a completely unrelated card several hours away.
            return -1;
        }
        return legacyIndex >= 0 && legacyIndex < rebuiltIds.size() ? legacyIndex : -1;
    }
}
