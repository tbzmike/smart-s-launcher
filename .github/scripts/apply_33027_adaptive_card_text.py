from pathlib import Path


def replace_exact(path, old, new, expected=1):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} occurrences, found {count}: {old!r}")
    p.write_text(text.replace(old, new))

f = "app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java"
replace_exact(f, "import fr.neamar.kiss.ui.AutoMarqueeTextView;", "import fr.neamar.kiss.ui.VerticalCardTextView;")
replace_exact(f, "AutoMarqueeTextView", "VerticalCardTextView", expected=6)
replace_exact(f, "ViewGroup.LayoutParams.MATCH_PARENT, dp(31) * Math.max(90, namePercent) / 100));", "ViewGroup.LayoutParams.MATCH_PARENT, scaledTextHeight(31, heightPercent, namePercent)));", expected=2)
replace_exact(f, "ViewGroup.LayoutParams.MATCH_PARENT, dp(27)));", "ViewGroup.LayoutParams.MATCH_PARENT, scaledTextHeight(27, heightPercent, 100)));", expected=1)
replace_exact(f, "ViewGroup.LayoutParams.MATCH_PARENT, dp(31)));", "ViewGroup.LayoutParams.MATCH_PARENT, scaledTextHeight(31, heightPercent, 100)));", expected=1)
replace_exact(f, "ViewGroup.LayoutParams.MATCH_PARENT, dp(25)));", "ViewGroup.LayoutParams.MATCH_PARENT, scaledTextHeight(25, heightPercent, 100)));", expected=1)
replace_exact(f, "ViewGroup.LayoutParams.MATCH_PARENT, dp(34) * Math.max(90, namePercent) / 100);", "ViewGroup.LayoutParams.MATCH_PARENT, scaledTextHeight(34, heightPercent, namePercent));", expected=1)
replace_exact(f, "    private int dp(int value) {\n", "    private int scaledTextHeight(int baseDp, int heightPercent, int textPercent) {\n        int cardScale = Math.max(70, heightPercent);\n        int textScale = Math.max(90, textPercent);\n        return dp(baseDp) * cardScale * textScale / 10000;\n    }\n\n    private int dp(int value) {\n")

b = "app/build.gradle"
replace_exact(b, "// Smart S Launcher 3.30.26 - isolate Vertical Cards from IME focus\n        versionCode 454\n        versionName \"3.30.26\"", "// Smart S Launcher 3.30.27 - adaptive Vertical Cards text fitting\n        versionCode 455\n        versionName \"3.30.27\"")
