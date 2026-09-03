from pathlib import Path


def replace_exact(path, old, new, expected=1):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} occurrences, found {count}: {old!r}")
    p.write_text(text.replace(old, new, expected))

f = "app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java"

# Preserve the old visual minimum, but do not use a fixed height that physically clips 3/4 lines.
replacements = [
    (
        "        center.addView(cardTitle, new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, scaledTextHeight(31, heightPercent, namePercent)));\n",
        "        cardTitle.setMinHeight(scaledTextHeight(31, heightPercent, namePercent));\n        center.addView(cardTitle, new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n",
    ),
    (
        "            center.addView(meta, new LinearLayout.LayoutParams(\n                    ViewGroup.LayoutParams.MATCH_PARENT, scaledTextHeight(27, heightPercent, 100)));\n",
        "            meta.setMinHeight(scaledTextHeight(27, heightPercent, 100));\n            center.addView(meta, new LinearLayout.LayoutParams(\n                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n",
    ),
    (
        "            center.addView(lastMessage, new LinearLayout.LayoutParams(\n                    ViewGroup.LayoutParams.MATCH_PARENT, scaledTextHeight(31, heightPercent, 100)));\n",
        "            lastMessage.setMinHeight(scaledTextHeight(31, heightPercent, 100));\n            center.addView(lastMessage, new LinearLayout.LayoutParams(\n                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n",
    ),
    (
        "            center.addView(context, new LinearLayout.LayoutParams(\n                    ViewGroup.LayoutParams.MATCH_PARENT, scaledTextHeight(25, heightPercent, 100)));\n",
        "            context.setMinHeight(scaledTextHeight(25, heightPercent, 100));\n            center.addView(context, new LinearLayout.LayoutParams(\n                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n",
    ),
    (
        "            center.addView(callerName, new LinearLayout.LayoutParams(\n                    ViewGroup.LayoutParams.MATCH_PARENT, scaledTextHeight(31, heightPercent, namePercent)));\n",
        "            callerName.setMinHeight(scaledTextHeight(31, heightPercent, namePercent));\n            center.addView(callerName, new LinearLayout.LayoutParams(\n                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n",
    ),
    (
        "        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, scaledTextHeight(34, heightPercent, namePercent));\n",
        "        name.setMinHeight(scaledTextHeight(34, heightPercent, namePercent));\n        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);\n",
    ),
]
for old, new in replacements:
    replace_exact(f, old, new)

replace_exact(
    f,
    "                configureCollapsedMessage(activeText, textLineBudget);\n",
    "                configureCollapsedMessage(activeText, textLineBudget);\n                activeText.setMinHeight(0);\n                activeText.setMaxHeight(Integer.MAX_VALUE);\n",
)

# Version bump only; build workflow is updated separately after this source gate passes.
b = "app/build.gradle"
replace_exact(
    b,
    "// Smart S Launcher 3.30.28 - true 2-to-4-line Vertical Cards text\n        versionCode 456\n        versionName \"3.30.28\"",
    "// Smart S Launcher 3.30.29 - remove Vertical Cards multiline height clipping\n        versionCode 457\n        versionName \"3.30.29\"",
)
