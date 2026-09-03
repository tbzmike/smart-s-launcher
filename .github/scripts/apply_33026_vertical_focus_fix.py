from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one guarded anchor, found {count}")
    p.write_text(text.replace(old, new, 1))

cards = "app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java"

replace_once(
    cards,
    '''    private boolean isActiveQuery() {\n        return mainActivity.searchEditText != null\n                && !TextUtils.isEmpty(mainActivity.searchEditText.getText());\n    }\n\n    private void applyState(boolean force) {''',
    '''    private boolean isActiveQuery() {\n        return mainActivity.searchEditText != null\n                && !TextUtils.isEmpty(mainActivity.searchEditText.getText());\n    }\n\n    /**\n     * While the IME search field owns text input, the rebuilt card tree must not enter Android's\n     * focus-navigation graph. Clickable Views are automatically focusable on modern Android, and\n     * this renderer recreates several clickable descendants per result. Blocking descendants only\n     * for an active query keeps touch interaction intact while preventing relayout from stealing\n     * EditText/IME focus.\n     */\n    private void applySearchFocusIsolation(boolean activeQuery) {\n        if (scroller == null) return;\n        scroller.setDescendantFocusability(activeQuery\n                ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);\n        scroller.setFocusable(!activeQuery);\n        scroller.setFocusableInTouchMode(false);\n        if (column != null) {\n            column.setFocusable(false);\n            column.setFocusableInTouchMode(false);\n        }\n    }\n\n    private void applyState(boolean force) {''')

replace_once(
    cards,
    '''    private void rebuild() {\n        if (column == null || mainActivity.adapter == null) return;\n        boolean activeQuery = isActiveQuery();\n        Map<String, NotificationHistoryRecord> latestNotifications =''',
    '''    private void rebuild() {\n        if (column == null || mainActivity.adapter == null) return;\n        boolean activeQuery = isActiveQuery();\n        boolean preserveSearchFocus = activeQuery\n                && mainActivity.searchEditText != null\n                && mainActivity.searchEditText.hasFocus();\n        applySearchFocusIsolation(activeQuery);\n        Map<String, NotificationHistoryRecord> latestNotifications =''')

replace_once(
    cards,
    '''        if (!activeQuery) {\n            scroller.post(() -> {\n                int childCount = column.getChildCount();\n                int first = Math.max(0, childCount - 16);\n                int visualIndex = 0;\n                for (int i = first; i < childCount; i++) {\n                    View child = column.getChildAt(i);\n                    animateIn(child, visualIndex++);\n                }\n            });\n        }\n    }''',
    '''        if (preserveSearchFocus && !mainActivity.searchEditText.hasFocus()) {\n            // Restore only a focus state that existed before this rebuild. This is not an\n            // unconditional IME reopen: it simply prevents card-tree replacement from ending\n            // an active typing session.\n            mainActivity.showKeyboard();\n        }\n\n        if (!activeQuery) {\n            scroller.post(() -> {\n                int childCount = column.getChildCount();\n                int first = Math.max(0, childCount - 16);\n                int visualIndex = 0;\n                for (int i = first; i < childCount; i++) {\n                    View child = column.getChildAt(i);\n                    animateIn(child, visualIndex++);\n                }\n            });\n        }\n    }''')

# Explicitly exclude the touch-clickable card surfaces from automatic focusability too. This is
# intentionally redundant with FOCUS_BLOCK_DESCENDANTS during active queries and protects against
# transient focus assignment while the rebuilt tree is being attached.
replace_once(
    cards,
    '''        card.setClickable(true);\n        cardTitle.setClickable(true);\n        name.setClickable(true);\n        return wrapper;''',
    '''        card.setClickable(true);\n        cardTitle.setClickable(true);\n        name.setClickable(true);\n        card.setFocusable(false);\n        card.setFocusableInTouchMode(false);\n        cardTitle.setFocusable(false);\n        cardTitle.setFocusableInTouchMode(false);\n        name.setFocusable(false);\n        name.setFocusableInTouchMode(false);\n        return wrapper;''')

replace_once(
    "app/build.gradle",
    '''        // Smart S Launcher 3.30.25 - stable Vertical Cards search and keyboard\n        versionCode 453\n        versionName "3.30.25"''',
    '''        // Smart S Launcher 3.30.26 - isolate Vertical Cards from IME focus\n        versionCode 454\n        versionName "3.30.26"''')

# Fail closed on final invariants.
c = Path(cards).read_text()
g = Path("app/build.gradle").read_text()
assert "FOCUS_BLOCK_DESCENDANTS" in c
assert "boolean preserveSearchFocus" in c
assert "mainActivity.showKeyboard();" in c
assert "card.setFocusable(false);" in c
assert 'versionCode 454' in g and 'versionName "3.30.26"' in g
