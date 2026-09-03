from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1))


# 1. Keep marquee behavior, but only animate text that actually overflows.
path = "app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java"
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

# 2. Bound accent state and reuse per-ImageView halo drawables.
path = "app/src/main/java/fr/neamar/kiss/ui/TileVisualStyle.java"
replace_once(path, "import android.view.View;\n", "import android.util.LruCache;\nimport android.view.View;\n")
replace_once(path, "import java.util.concurrent.ConcurrentHashMap;\n", "import java.util.WeakHashMap;\n")
replace_once(
    path,
    "    private static final ConcurrentHashMap<Long, Integer> ACCENT_CACHE = new ConcurrentHashMap<>();\n",
    "    private static final int MAX_ACCENT_ENTRIES = 512;\n"
    "    private static final LruCache<Long, Integer> ACCENT_CACHE = new LruCache<>(MAX_ACCENT_ENTRIES);\n"
    "    private static final WeakHashMap<ImageView, HaloState> HALO_CACHE = new WeakHashMap<>();\n",
)
replace_once(
    path,
    "            accent = ACCENT_CACHE.computeIfAbsent(result.getUniqueId(), ignored -> sampleAccent(icon));\n",
    "            accent = cachedAccent(result.getUniqueId(), icon);\n",
)
old = """        GradientDrawable halo = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{tone(accent, 1.20f, 88), tone(accent, 0.72f, 52)});
        halo.setCornerRadius(dp(context, 14));
        primary.setBackground(halo);
        primary.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
    }
"""
new = """        HaloState haloState = HALO_CACHE.get(primary);
        if (haloState == null || haloState.accent != accent) {
            GradientDrawable halo = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{tone(accent, 1.20f, 88), tone(accent, 0.72f, 52)});
            halo.setCornerRadius(dp(context, 14));
            haloState = new HaloState(accent, halo);
            HALO_CACHE.put(primary, haloState);
        }
        if (primary.getBackground() != haloState.drawable) primary.setBackground(haloState.drawable);
        int inset = dp(context, 4);
        if (primary.getPaddingLeft() != inset || primary.getPaddingTop() != inset
                || primary.getPaddingRight() != inset || primary.getPaddingBottom() != inset) {
            primary.setPadding(inset, inset, inset, inset);
        }
    }

    private static int cachedAccent(long resultId, Drawable icon) {
        synchronized (ACCENT_CACHE) {
            Integer cached = ACCENT_CACHE.get(resultId);
            if (cached != null) return cached;
        }
        int sampled = sampleAccent(icon);
        synchronized (ACCENT_CACHE) {
            ACCENT_CACHE.put(resultId, sampled);
        }
        return sampled;
    }
"""
replace_once(path, old, new)
replace_once(
    path,
    "        int count = 0;\n        for (int y = 0; y < SAMPLE_SIZE; y++) {\n",
    "        int count = 0;\n        float[] hsv = new float[3];\n        for (int y = 0; y < SAMPLE_SIZE; y++) {\n",
)
replace_once(path, "                float[] hsv = new float[3];\n                Color.colorToHSV(color, hsv);\n", "                Color.colorToHSV(color, hsv);\n")
replace_once(
    path,
    "    private static final class IconState {\n",
    "    private static final class HaloState {\n"
    "        final int accent;\n"
    "        final GradientDrawable drawable;\n\n"
    "        HaloState(int accent, GradientDrawable drawable) {\n"
    "            this.accent = accent;\n"
    "            this.drawable = drawable;\n"
    "        }\n"
    "    }\n\n"
    "    private static final class IconState {\n",
)

# 3. Avoid rebuilding grouped launch statistics every two seconds.
path = "app/src/main/java/fr/neamar/kiss/ui/UniversalHistoryTimestamp.java"
replace_once(
    path,
    "    private static final long STATS_REFRESH_MS = 2_000L;\n",
    "    private static final long STATS_REFRESH_MS = 30_000L;\n"
    "    private static final int MAX_FIRST_SEEN_ENTRIES = 512;\n",
)
replace_once(
    path,
    "        return FIRST_SEEN.computeIfAbsent(key, ignored -> System.currentTimeMillis());\n",
    "        if (FIRST_SEEN.size() >= MAX_FIRST_SEEN_ENTRIES && !FIRST_SEEN.containsKey(key)) {\n"
    "            FIRST_SEEN.clear();\n"
    "        }\n"
    "        return FIRST_SEEN.computeIfAbsent(key, ignored -> System.currentTimeMillis());\n",
)
marker = "    private static CharSequence formatTimestamp(Context context, long timestamp,\n"
insert = """    public static void invalidateStats() {
        statsLoadedAt = 0L;
    }

"""
replace_once(path, marker, insert + marker)

# 4. Close the SQLiteOpenHelper after each grouped statistics snapshot.
path = "app/src/main/java/fr/neamar/kiss/db/LaunchStatsProvider.java"
old = """        HashMap<String, LaunchStats> stats = new HashMap<>();
        SQLiteDatabase db = new DB(context.getApplicationContext()).getReadableDatabase();
        String sql = "SELECT record, MAX(timeStamp), "
                + "SUM(CASE WHEN timeStamp >= ? THEN 1 ELSE 0 END), COUNT(*) "
                + "FROM history GROUP BY record";
        try (Cursor cursor = db.rawQuery(sql, new String[]{Long.toString(startOfToday)})) {
            while (cursor.moveToNext()) {
                stats.put(cursor.getString(0), new LaunchStats(
                        cursor.getLong(1), cursor.getInt(2), cursor.getInt(3)));
            }
        }
        return stats;
"""
new = """        HashMap<String, LaunchStats> stats = new HashMap<>();
        DB helper = new DB(context.getApplicationContext());
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            String sql = "SELECT record, MAX(timeStamp), "
                    + "SUM(CASE WHEN timeStamp >= ? THEN 1 ELSE 0 END), COUNT(*) "
                    + "FROM history GROUP BY record";
            try (Cursor cursor = db.rawQuery(sql, new String[]{Long.toString(startOfToday)})) {
                while (cursor.moveToNext()) {
                    stats.put(cursor.getString(0), new LaunchStats(
                            cursor.getLong(1), cursor.getInt(2), cursor.getInt(3)));
                }
            }
        } finally {
            helper.close();
        }
        return stats;
"""
replace_once(path, old, new)

# 5. Invalidate the grouped snapshot immediately after a launch is recorded.
path = "app/src/main/java/fr/neamar/kiss/result/Result.java"
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
