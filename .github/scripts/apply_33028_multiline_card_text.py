from pathlib import Path


def replace_exact(path, old, new, expected=1):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} occurrences, found {count}: {old!r}")
    p.write_text(text.replace(old, new, expected))

# VerticalCardTextView: never collapse wrapped card text back to a one-line marquee.
v = "app/src/main/java/fr/neamar/kiss/ui/VerticalCardTextView.java"
replace_exact(v,
"    private boolean applyingFit;\n",
"    private boolean applyingFit;\n    private int lineBudget = 2;\n")
replace_exact(v,
"    @Override\n    protected void onAttachedToWindow() {\n",
"    public void setLineBudget(int lines) {\n        lineBudget = Math.max(2, Math.min(4, lines));\n        if (isAttachedToWindow()) post(this::applyBestFitMode);\n    }\n\n    @Override\n    protected void onAttachedToWindow() {\n")
old_fit = '''    private void applyBestFitMode() {
        if (applyingFit) return;
        int availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int availableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        CharSequence value = getText();
        if (availableWidth <= 0 || availableHeight <= 0 || TextUtils.isEmpty(value)) return;

        StaticLayout wrapped = new StaticLayout(
                value,
                getPaint(),
                availableWidth,
                Layout.Alignment.ALIGN_NORMAL,
                getLineSpacingMultiplier(),
                getLineSpacingExtra(),
                getIncludeFontPadding());
        boolean fitsWrapped = wrapped.getHeight() <= availableHeight;

        applyingFit = true;
        try {
            if (fitsWrapped) {
                setSelected(false);
                setHorizontallyScrolling(false);
                setSingleLine(false);
                setMaxLines(Math.max(1, wrapped.getLineCount()));
                setEllipsize(null);
            } else {
                setSingleLine(true);
                setMaxLines(1);
                setHorizontallyScrolling(true);
                setEllipsize(TextUtils.TruncateAt.MARQUEE);
                setMarqueeRepeatLimit(-1);
                setSelected(true);
            }
        } finally {
            applyingFit = false;
        }
    }
'''
new_fit = '''    private void applyBestFitMode() {
        if (applyingFit || TextUtils.isEmpty(getText())) return;
        applyingFit = true;
        try {
            // Vertical Cards must use their available height before truncating. Never fall back
            // to a one-line marquee merely because the complete value needs more than one line.
            setSelected(false);
            setHorizontallyScrolling(false);
            setSingleLine(false);
            setMaxLines(lineBudget);
            setEllipsize(TextUtils.TruncateAt.END);
        } finally {
            applyingFit = false;
        }
    }
'''
replace_exact(v, old_fit, new_fit)

# SmartCardListForwarder: assign 2-4 lines from the actual card-height preference and fix the
# re-parented active-notification TextView, which 3.30.27 still hard-locked to one line.
f = "app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java"
replace_exact(f,
"import android.text.TextUtils;\n",
"import android.text.Layout;\nimport android.text.TextUtils;\n")
replace_exact(f,
"        int minimumCardHeight = Math.max(dp(96), dp(122) * heightPercent / 100);\n",
"        int minimumCardHeight = Math.max(dp(96), dp(122) * heightPercent / 100);\n        int textLineBudget = cardTextLineBudget(heightPercent);\n")

# Every VerticalCardTextView created by Vertical Cards gets the same height-derived line budget.
for declaration in [
    "VerticalCardTextView cardTitle = new VerticalCardTextView(mainActivity);",
    "VerticalCardTextView meta = new VerticalCardTextView(mainActivity);",
    "VerticalCardTextView lastMessage = new VerticalCardTextView(mainActivity);",
    "VerticalCardTextView context = new VerticalCardTextView(mainActivity);",
    "VerticalCardTextView callerName = new VerticalCardTextView(mainActivity);",
    "VerticalCardTextView name = new VerticalCardTextView(mainActivity);",
]:
    var = declaration.split()[1]
    replace_exact(f, declaration + "\n", declaration + f"\n        {var}.setLineBudget(textLineBudget);\n")

replace_exact(f,
"                configureCollapsedMessage(activeText);\n",
"                configureCollapsedMessage(activeText, textLineBudget);\n")

old_collapsed = '''    private void configureCollapsedMessage(TextView text) {
        text.setSingleLine(true);
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        text.setMarqueeRepeatLimit(-1);
        text.setHorizontallyScrolling(true);
        text.setSelected(true);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13f);
        text.setGravity(Gravity.START);
        text.setPadding(0, dp(2), 0, dp(2));
    }
'''
new_collapsed = '''    private void configureCollapsedMessage(TextView text, int lineBudget) {
        text.setSelected(false);
        text.setHorizontallyScrolling(false);
        text.setSingleLine(false);
        text.setMaxLines(lineBudget);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13f);
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        text.setPadding(0, dp(2), 0, dp(2));
    }
'''
replace_exact(f, old_collapsed, new_collapsed)

old_needs = '''    private boolean messageNeedsExpansion(TextView text) {
        CharSequence value = text.getText();
        if (TextUtils.isEmpty(value)) return false;
        int available = text.getWidth() - text.getPaddingLeft() - text.getPaddingRight();
        if (available <= 0) return true;
        float measured = text.getPaint().measureText(value.toString());
        return measured > available;
    }
'''
new_needs = '''    private boolean messageNeedsExpansion(TextView text) {
        CharSequence value = text.getText();
        if (TextUtils.isEmpty(value)) return false;
        Layout layout = text.getLayout();
        if (layout == null || layout.getLineCount() == 0) return false;
        int lastLine = layout.getLineCount() - 1;
        return layout.getEllipsisCount(lastLine) > 0
                || layout.getLineEnd(lastLine) < value.length();
    }
'''
replace_exact(f, old_needs, new_needs)

replace_exact(f,
"    private int scaledTextHeight(int baseDp, int heightPercent, int textPercent) {\n",
"    private int cardTextLineBudget(int heightPercent) {\n        if (heightPercent >= 145) return 4;\n        if (heightPercent >= 115) return 3;\n        return 2;\n    }\n\n    private int scaledTextHeight(int baseDp, int heightPercent, int textPercent) {\n")

# Version bump.
b = "app/build.gradle"
replace_exact(b,
"// Smart S Launcher 3.30.27 - adaptive Vertical Cards text fitting\n        versionCode 455\n        versionName \"3.30.27\"",
"// Smart S Launcher 3.30.28 - true 2-to-4-line Vertical Cards text\n        versionCode 456\n        versionName \"3.30.28\"")
