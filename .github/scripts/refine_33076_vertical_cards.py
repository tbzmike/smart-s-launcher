from pathlib import Path

FILE = Path('app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java')
text = FILE.read_text()


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected exactly one match, found {count}: {old[:100]!r}')
    text = text.replace(old, new, 1)

replace_once(
    'import fr.neamar.kiss.ui.AutoMarqueeTextView;\n',
    'import fr.neamar.kiss.ui.AutoMarqueeTextView;\n'
    'import fr.neamar.kiss.ui.AutoScrollPreviewTextView;\n')

replace_once(
    '''        ImageView liveIcon = findIconView(source);\n        Drawable iconDrawable = liveIcon == null ? null : liveIcon.getDrawable();\n        if (iconDrawable == null) iconDrawable = result.getDrawable(mainActivity);\n        int accent = accentFor(result, iconDrawable);\n        styleCard(card, radiusDp, accent);\n''',
    '''        ImageView liveIcon = findIconView(source);\n        Drawable iconDrawable = liveIcon == null ? null : liveIcon.getDrawable();\n        // Match Horizontal Icons' smooth-scroll rule: never turn a cold asynchronous icon into a\n        // synchronous drawable load on the UI thread just to style the card. A neutral provisional\n        // accent is deliberately not cached; bindDrawable() below supplies the real icon/accent.\n        int accent = iconDrawable == null\n                ? Color.rgb(64, 84, 118) : accentFor(result, iconDrawable);\n        styleCard(card, radiusDp, accent);\n''')

replace_once(
    '''        int iconSize = Math.min(dp(88), dp(66) * iconPercent / 100);\n        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);\n        iconLp.rightMargin = dp(14);\n        mainRow.addView(iconView, iconLp);\n\n        LinearLayout center = new LinearLayout(mainActivity);\n''',
    '''        int iconSize = Math.min(dp(88), dp(66) * iconPercent / 100);\n        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);\n        iconLp.rightMargin = dp(14);\n        mainRow.addView(iconView, iconLp);\n        if (iconView instanceof ImageView) {\n            ImageView renderedIcon = (ImageView) iconView;\n            result.bindDrawable(renderedIcon, drawable -> {\n                if (drawable == null || renderedIcon.getParent() == null) return;\n                int resolvedAccent = accentFor(result, drawable);\n                styleCard(card, radiusDp, resolvedAccent);\n            });\n        }\n\n        LinearLayout center = new LinearLayout(mainActivity);\n''')

replace_once(
    '''        } else if (hasMessage) {\n            AutoMarqueeTextView lastMessage = new AutoMarqueeTextView(mainActivity);\n''',
    '''        } else if (hasMessage) {\n            AutoScrollPreviewTextView lastMessage = new AutoScrollPreviewTextView(mainActivity);\n''')

FILE.write_text(text)
print('Vertical Cards hot-path patch applied')
