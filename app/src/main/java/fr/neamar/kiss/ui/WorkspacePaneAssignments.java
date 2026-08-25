package fr.neamar.kiss.ui;

import android.content.SharedPreferences;

import java.util.Arrays;

/**
 * Single source of truth for mapping the authoritative history and widget containers to workspace
 * positions. A live container is never cloned: every mapping contains it exactly once and leaves
 * any remaining four-pane positions empty.
 */
public final class WorkspacePaneAssignments {
    public static final String PREF_TWO_PANE_HISTORY_POSITION =
            "smart-workspace-two-pane-history-position";
    public static final String PREF_FOUR_PANE_HISTORY_POSITION =
            "smart-workspace-four-pane-history-position";
    public static final String PREF_FOUR_PANE_WIDGETS_POSITION =
            "smart-workspace-four-pane-widgets-position";
    public static final String PREF_ASSIGNMENTS_MIGRATED =
            "smart-workspace-pane-assignments-migrated";

    public enum Content {
        APPS_AND_HISTORY,
        WIDGETS,
        EMPTY
    }

    private WorkspacePaneAssignments() { }

    public static Content[] resolve(int paneCount, int historyPosition, int widgetsPosition) {
        if (paneCount <= 0) return new Content[0];
        Content[] result = new Content[paneCount];
        Arrays.fill(result, Content.EMPTY);

        int history = normalizePosition(historyPosition, 1, paneCount);
        int widgets = normalizePosition(widgetsPosition, Math.min(2, paneCount), paneCount);
        if (widgets == history && paneCount > 1) widgets = firstPositionExcept(paneCount, history);

        result[history - 1] = Content.APPS_AND_HISTORY;
        if (widgets != history) result[widgets - 1] = Content.WIDGETS;
        return result;
    }

    public static int readPosition(SharedPreferences prefs, String key,
                                   int fallback, int paneCount) {
        Object raw = prefs.getAll().get(key);
        int value = fallback;
        if (raw instanceof Number) {
            value = ((Number) raw).intValue();
        } else if (raw instanceof String) {
            try {
                value = Integer.parseInt((String) raw);
            } catch (NumberFormatException ignored) {
                value = fallback;
            }
        }
        return normalizePosition(value, fallback, paneCount);
    }

    public static int normalizePosition(int position, int fallback, int paneCount) {
        if (paneCount <= 0) return 0;
        int safeFallback = Math.max(1, Math.min(paneCount, fallback));
        return position >= 1 && position <= paneCount ? position : safeFallback;
    }

    public static int firstPositionExcept(int paneCount, int occupiedPosition) {
        for (int position = 1; position <= paneCount; position++) {
            if (position != occupiedPosition) return position;
        }
        return normalizePosition(occupiedPosition, 1, paneCount);
    }
}
