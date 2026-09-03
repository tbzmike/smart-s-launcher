from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one guarded anchor, found {count}")
    p.write_text(text.replace(old, new, 1))


# 1) Vertical Cards owns IME/viewport geometry. Do not also resize/scroll the hidden ListView.
experience = "app/src/main/java/fr/neamar/kiss/forwarder/ExperienceTweaks.java"
replace_once(
    experience,
    '''        VerticalCardKeyboardAnchor.onKeyboardVisibilityChanged(mainActivity, keyboardIsVisible);\n        if (!keyboardIsVisible) return;\n\n        // Normal KISS list path. Vertical Cards have their own ScrollView and are anchored by\n        // VerticalCardKeyboardAnchor above; do not assume mainActivity.list is the visible surface.\n        if (mainActivity.hider != null) mainActivity.hider.fixScroll();''',
    '''        VerticalCardKeyboardAnchor.onKeyboardVisibilityChanged(mainActivity, keyboardIsVisible);\n        if (!keyboardIsVisible) return;\n\n        // Vertical Cards own IME geometry through VerticalCardViewportController. Running the\n        // normal hidden ListView resize/scroll path at the same time creates competing layout\n        // mutations during typing and can destabilize IME focus.\n        if (HistoryDisplayForwarder.VERTICAL_CARDS.equals(prefs.getString(\n                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL))) {\n            return;\n        }\n\n        // Normal KISS list path.\n        if (mainActivity.hider != null) mainActivity.hider.fixScroll();''')

# 2) Keep the renderer + viewport behavior for queries, but do not run history-only enrichers
#    for ordinary query publications.
manager = "app/src/main/java/fr/neamar/kiss/forwarder/ForwarderManager.java"
replace_once(
    manager,
    '''        if (isVerticalCardsMode()) {\n            verticalCardViewportController.beforeDataSetChanged();\n            smartCardListForwarder.onDataSetChanged();\n            verticalMapsCardForwarder.onDataSetChanged();\n            verticalCardGroupResizeController.onDataSetChanged();\n            verticalCardNotificationHistoryForwarder.onDataSetChanged();\n            verticalCardUsageForwarder.onDataSetChanged();\n            verticalCardViewportController.afterDataSetChanged();\n        } else if (isSquareMode()) {''',
    '''        if (isVerticalCardsMode()) {\n            verticalCardViewportController.beforeDataSetChanged();\n            smartCardListForwarder.onDataSetChanged();\n            if (isHistorySearch()) {\n                verticalMapsCardForwarder.onDataSetChanged();\n                verticalCardGroupResizeController.onDataSetChanged();\n                verticalCardNotificationHistoryForwarder.onDataSetChanged();\n                verticalCardUsageForwarder.onDataSetChanged();\n            }\n            verticalCardViewportController.afterDataSetChanged();\n        } else if (isSquareMode()) {''')

# 3) Typed queries do not need the history notification DB snapshot or card-entry replay.
cards = "app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java"
replace_once(
    cards,
    '''    private boolean isEnabled() {\n        return VERTICAL_CARDS.equals(\n                prefs.getString(HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));\n    }\n\n    private void applyState(boolean force) {''',
    '''    private boolean isEnabled() {\n        return VERTICAL_CARDS.equals(\n                prefs.getString(HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));\n    }\n\n    private boolean isActiveQuery() {\n        return mainActivity.searchEditText != null\n                && !TextUtils.isEmpty(mainActivity.searchEditText.getText());\n    }\n\n    private void applyState(boolean force) {''')

replace_once(
    cards,
    '''        Map<String, NotificationHistoryRecord> latestNotifications =\n                prefs.getBoolean("enable-notification-history", false)\n                        ? SmartStateStore.queryLatestNotificationsByPackage(mainActivity)\n                        : Collections.emptyMap();\n        column.removeAllViews();''',
    '''        boolean activeQuery = isActiveQuery();\n        Map<String, NotificationHistoryRecord> latestNotifications =\n                !activeQuery && prefs.getBoolean("enable-notification-history", false)\n                        ? SmartStateStore.queryLatestNotificationsByPackage(mainActivity)\n                        : Collections.emptyMap();\n        column.removeAllViews();''')

replace_once(
    cards,
    '''        scroller.post(() -> {\n            int childCount = column.getChildCount();\n            int first = Math.max(0, childCount - 16);\n            int visualIndex = 0;\n            for (int i = first; i < childCount; i++) {\n                View child = column.getChildAt(i);\n                animateIn(child, visualIndex++);\n            }\n        });''',
    '''        if (!activeQuery) {\n            scroller.post(() -> {\n                int childCount = column.getChildCount();\n                int first = Math.max(0, childCount - 16);\n                int visualIndex = 0;\n                for (int i = first; i < childCount; i++) {\n                    View child = column.getChildAt(i);\n                    animateIn(child, visualIndex++);\n                }\n            });\n        }''')

# 4) Version + CI artifact identity.
replace_once(
    "app/build.gradle",
    '''        // Smart S Launcher 3.30.24 - visible independent search/history result limits\n        versionCode 452\n        versionName "3.30.24"''',
    '''        // Smart S Launcher 3.30.25 - stable Vertical Cards search and keyboard\n        versionCode 453\n        versionName "3.30.25"''')

build = ".github/workflows/build.yml"
text = Path(build).read_text()
if text.count("3.30.24") != 7:
    raise SystemExit(f"build workflow: expected 7 version-name markers, found {text.count('3.30.24')}")
if text.count("452") != 2:
    raise SystemExit(f"build workflow: expected 2 version-code markers, found {text.count('452')}")
text = text.replace("3.30.24", "3.30.25")
text = text.replace("452", "453")
if "3.30.24" in text or "452" in text:
    raise SystemExit("build workflow still contains 3.30.24/452")
Path(build).write_text(text)

# Guard final invariants before CI is allowed to build/commit.
e = Path(experience).read_text()
m = Path(manager).read_text()
c = Path(cards).read_text()
g = Path("app/build.gradle").read_text()
w = Path(build).read_text()
assert "Vertical Cards own IME geometry" in e
assert "if (isHistorySearch())" in m
assert "boolean activeQuery = isActiveQuery();" in c
assert '!activeQuery && prefs.getBoolean("enable-notification-history", false)' in c
assert "if (!activeQuery)" in c
assert 'versionCode 453' in g and 'versionName "3.30.25"' in g
assert "build/3.30.25" in w and "3.30.25-debug" in w
