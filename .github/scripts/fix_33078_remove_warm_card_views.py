from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}\n--- needle ---\n{old[:900]}")
    p.write_text(text.replace(old, new, 1))


path = "app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java"

# Never retain Activity-owned View trees while another app is foreground. The 3.30.77 warm-card
# map kept the complete Home hierarchy alive alongside the query hierarchy, including icons,
# listeners and notification Views. Keep the lightweight Result/icon caches instead.
replace_once(
    path,
    '''    private final Map<String, String> activeQueryCardSignatures = new HashMap<>();\n    private final Map<String, View> warmHistoryCards = new LinkedHashMap<>();\n    private int warmHistoryScrollY;\n    private final Map<Long, Integer> accentCache =''',
    '''    private final Map<String, String> activeQueryCardSignatures = new HashMap<>();\n    private final Map<Long, Integer> accentCache =''')

replace_once(
    path,
    '''        activeQueryCardSignatures.clear();\n        warmHistoryCards.clear();\n        accentCache.clear();''',
    '''        activeQueryCardSignatures.clear();\n        accentCache.clear();''')

replace_once(
    path,
    '''            if (activeQuery && !previouslyRenderedActiveQuery) captureWarmHistoryCards();\n            boolean restoredWarmHistory = !activeQuery && previouslyRenderedActiveQuery\n                    && restoreWarmHistoryCards(latestNotifications);\n            activeQueryCardSignatures.clear();\n\n            if (!restoredWarmHistory) {\n                column.removeAllViews();''',
    '''            activeQueryCardSignatures.clear();\n            column.removeAllViews();''')

replace_once(
    path,
    '''                    if (activeQuery) {\n                        activeQueryCardSignatures.put(\n                                result.getPojoId(), activeQueryCardSignature(result));\n                    }\n                }\n            } else {\n                animateHistoryItems = false;\n            }\n        }''',
    '''                    if (activeQuery) {\n                        activeQueryCardSignatures.put(\n                                result.getPojoId(), activeQueryCardSignature(result));\n                    }\n                }\n        }''')

replace_once(
    path,
    '''    private void captureWarmHistoryCards() {\n        warmHistoryCards.clear();\n        warmHistoryScrollY = scroller == null ? 0 : scroller.getScrollY();\n        if (column == null) return;\n        for (int i = 0; i < column.getChildCount(); i++) {\n            View child = column.getChildAt(i);\n            Object tag = child.getTag();\n            if (tag instanceof String) warmHistoryCards.put((String) tag, child);\n        }\n    }\n\n    private boolean restoreWarmHistoryCards(\n            Map<String, NotificationHistoryRecord> latestNotifications) {\n        if (column == null || mainActivity.adapter == null || warmHistoryCards.isEmpty()) return false;\n        Map<String, View> available = new LinkedHashMap<>(warmHistoryCards);\n        column.removeAllViews();\n        int count = mainActivity.adapter.getCount();\n        for (int position = 0; position < count; position++) {\n            Result<?> result = mainActivity.adapter.getItem(position);\n            View item = available.remove(result.getPojoId());\n            if (item == null) {\n                View source = mainActivity.adapter.getView(position, null, column);\n                item = createCardItem(source, result, position, latestNotifications);\n            } else if (item.getParent() instanceof ViewGroup) {\n                ((ViewGroup) item.getParent()).removeView(item);\n            }\n            column.addView(item);\n        }\n        warmHistoryCards.clear();\n        column.requestLayout();\n        column.invalidate();\n        int restoreY = warmHistoryScrollY;\n        scroller.post(() -> {\n            if (scroller == null || column == null) return;\n            int max = Math.max(0, column.getHeight() - scroller.getHeight());\n            scroller.scrollTo(0, Math.max(0, Math.min(max, restoreY)));\n        });\n        return true;\n    }\n\n''',
    '''''')

print("3.30.78 unsafe warm Vertical Card View retention removed successfully")
