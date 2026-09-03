from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1))


# Remaining repair 1: only animate marquee text when it really overflows.
path = "app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java"
text = Path(path).read_text()
if "private final WeakHashMap<TextView, Boolean> marqueeObservers" not in text:
    replace_once(
        path,
        "import android.text.TextUtils;\n",
        "import android.text.Editable;\nimport android.text.TextUtils;\nimport android.text.TextWatcher;\n",
    )
    replace_once(
        path,
        "    private final WeakHashMap<View, Boolean> overflowConfigured = new WeakHashMap<>();\n",
        "    private final WeakHashMap<View, Boolean> overflowConfigured = new WeakHashMap<>();\n"
        "    private final WeakHashMap<TextView, Boolean> marqueeObservers = new WeakHashMap<>();\n",
    )
    old = """    private void configureMarquee(TextView text) {
        text.setSingleLine(true);
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        text.setMarqueeRepeatLimit(-1);
        text.setHorizontallyScrolling(true);
        text.setHorizontalFadingEdgeEnabled(true);
        text.setSelected(true);
        text.setFocusable(false);
        text.setFocusableInTouchMode(false);
        makeTextUseAvailableWidth(text);
    }
"""
    new = """    private void configureMarquee(TextView text) {
        text.setSingleLine(true);
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        text.setMarqueeRepeatLimit(-1);
        text.setHorizontallyScrolling(true);
        text.setHorizontalFadingEdgeEnabled(true);
        text.setFocusable(false);
        text.setFocusableInTouchMode(false);
        makeTextUseAvailableWidth(text);
        ensureMarqueeObserver(text);
        updateMarqueeActivation(text);
    }

    private void ensureMarqueeObserver(TextView text) {
        if (marqueeObservers.containsKey(text)) return;
        marqueeObservers.put(text, Boolean.TRUE);
        text.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateMarqueeActivation(text);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        text.addOnLayoutChangeListener((v, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) ->
                updateMarqueeActivation(text));
    }

    private void updateMarqueeActivation(TextView text) {
        CharSequence value = text.getText();
        int available = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        boolean overflow = available > 0 && !TextUtils.isEmpty(value)
                && text.getPaint().measureText(value.toString()) > available;
        if (text.isSelected() != overflow) text.setSelected(overflow);
    }
"""
    replace_once(path, old, new)


# Remaining repair 2: invalidate grouped history stats immediately after a launch is recorded.
path = "app/src/main/java/fr/neamar/kiss/result/Result.java"
text = Path(path).read_text()
if "UniversalHistoryTimestamp.invalidateStats();" not in text:
    replace_once(
        path,
        "import fr.neamar.kiss.ui.ListPopup;\n",
        "import fr.neamar.kiss.ui.ListPopup;\nimport fr.neamar.kiss.ui.UniversalHistoryTimestamp;\n",
    )
    replace_once(
        path,
        "            KissApplication.getApplication(context).getDataHandler().addToHistory(pojo.getHistoryId());\n",
        "            KissApplication.getApplication(context).getDataHandler().addToHistory(pojo.getHistoryId());\n"
        "            UniversalHistoryTimestamp.invalidateStats();\n",
    )
