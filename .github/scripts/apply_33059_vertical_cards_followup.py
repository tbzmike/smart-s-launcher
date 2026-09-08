from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Vertical Cards search rendering: coalesce rapid provider/data-set churn instead of rebuilding
# the complete card hierarchy synchronously for every keystroke. History refresh behaviour remains
# unchanged, and clearing a query immediately restores history rather than leaving stale query cards.
path = "app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java"
replace_once(
    path,
    "    private static final int MAX_ACCENT_CACHE_SIZE = 256;\n",
    "    private static final int MAX_ACCENT_CACHE_SIZE = 256;\n"
    "    private static final long ACTIVE_QUERY_REBUILD_DEBOUNCE_MS = 64L;\n",
)
replace_once(
    path,
    "    private View edgeEffect;\n"
    "    private boolean pendingDataSetRefresh;\n",
    "    private View edgeEffect;\n"
    "    private boolean pendingDataSetRefresh;\n"
    "    private boolean renderedActiveQuery;\n"
    "    private final Runnable activeQueryRebuildRunnable = () -> {\n"
    "        if (isEnabled() && isActiveQuery()) rebuild();\n"
    "    };\n",
)
replace_once(
    path,
    "    void onDataSetChanged() {\n"
    "        if (!isEnabled()) return;\n"
    "        // Search results are directly user-driven and must stay live while typing. Idle History\n"
    "        // updates, including provider/background reconciliation, are deferred until the user next\n"
    "        // touches the card viewport. This keeps Vertical Cards as visually stable as Vertical List.\n"
    "        if (isActiveQuery() || column == null || column.getChildCount() == 0) {\n"
    "            rebuild();\n"
    "        } else {\n"
    "            pendingDataSetRefresh = true;\n"
    "        }\n"
    "    }\n",
    "    void onDataSetChanged() {\n"
    "        if (!isEnabled()) return;\n"
    "        // Search can publish several adapter updates for one input change. Rebuilding every\n"
    "        // card synchronously for each publication competes with the IME and causes visible\n"
    "        // typing stalls. Coalesce only active-query rebuilds; idle History keeps its existing\n"
    "        // deferred-refresh contract.\n"
    "        boolean activeQuery = isActiveQuery();\n"
    "        if (column == null || column.getChildCount() == 0\n"
    "                || (!activeQuery && renderedActiveQuery)) {\n"
    "            cancelPendingActiveQueryRebuild();\n"
    "            rebuild();\n"
    "        } else if (activeQuery) {\n"
    "            scheduleActiveQueryRebuild();\n"
    "        } else {\n"
    "            cancelPendingActiveQueryRebuild();\n"
    "            pendingDataSetRefresh = true;\n"
    "        }\n"
    "    }\n",
)
replace_once(
    path,
    "    void onDestroy() {\n"
    "        accentCache.clear();\n"
    "        container = null;\n",
    "    void onDestroy() {\n"
    "        cancelPendingActiveQueryRebuild();\n"
    "        accentCache.clear();\n"
    "        container = null;\n",
)
replace_once(
    path,
    "        pendingDataSetRefresh = false;\n"
    "    }\n\n"
    "    ScrollView getScroller() {\n",
    "        pendingDataSetRefresh = false;\n"
    "        renderedActiveQuery = false;\n"
    "    }\n\n"
    "    ScrollView getScroller() {\n",
)
replace_once(
    path,
    "    private void applySearchFocusIsolation(boolean activeQuery) {\n",
    "    private void scheduleActiveQueryRebuild() {\n"
    "        if (scroller == null) return;\n"
    "        scroller.removeCallbacks(activeQueryRebuildRunnable);\n"
    "        scroller.postDelayed(activeQueryRebuildRunnable, ACTIVE_QUERY_REBUILD_DEBOUNCE_MS);\n"
    "    }\n\n"
    "    private void cancelPendingActiveQueryRebuild() {\n"
    "        if (scroller != null) scroller.removeCallbacks(activeQueryRebuildRunnable);\n"
    "    }\n\n"
    "    private void applySearchFocusIsolation(boolean activeQuery) {\n",
)
replace_once(
    path,
    "    private void rebuild() {\n"
    "        if (column == null || mainActivity.adapter == null) return;\n"
    "        pendingDataSetRefresh = false;\n"
    "        boolean activeQuery = isActiveQuery();\n",
    "    private void rebuild() {\n"
    "        if (column == null || mainActivity.adapter == null) return;\n"
    "        cancelPendingActiveQueryRebuild();\n"
    "        pendingDataSetRefresh = false;\n"
    "        boolean activeQuery = isActiveQuery();\n"
    "        renderedActiveQuery = activeQuery;\n",
)


# Timestamp/usage metadata: bind by each card wrapper's stable pojo id rather than assuming the
# adapter's current numeric position is still the same after a history re-rank. This preserves the
# metadata attached to the visible card while provider/history updates are intentionally deferred.
path = "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java"
old = """        int count = Math.min(column.getChildCount(), mainActivity.adapter.getCount());
        for (int position = 0; position < count; position++) {
            View wrapper = column.getChildAt(position);
            Result<?> result = mainActivity.adapter.getItem(position);
            Pojo pojo = result == null ? null : result.getPojo();
            if (pojo == null) continue;
"""
new = """        Map<String, Result<?>> resultsByPojoId = new HashMap<>();
        for (int position = 0; position < mainActivity.adapter.getCount(); position++) {
            Result<?> result = mainActivity.adapter.getItem(position);
            if (result != null) resultsByPojoId.put(result.getPojoId(), result);
        }

        int count = column.getChildCount();
        for (int position = 0; position < count; position++) {
            View wrapper = column.getChildAt(position);
            Object wrapperId = wrapper.getTag();
            Result<?> result = wrapperId instanceof String
                    ? resultsByPojoId.get((String) wrapperId) : null;
            Pojo pojo = result == null ? null : result.getPojo();
            if (pojo == null) continue;
"""
replace_once(path, old, new)
