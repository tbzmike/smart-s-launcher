from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# Exact baseline: Smart S Launcher 3.30.74 verified source commit b17a3cae...

# 1. Version bump.
build_path = Path("app/build.gradle")
build = build_path.read_text()
build = replace_once(
    build,
    '        // Smart S Launcher 3.30.74 - smooth Home scrolling and marquee performance\n'
    '        versionCode 502\n'
    '        versionName "3.30.74"\n',
    '        // Smart S Launcher 3.30.75 - selectable auto-scroll or full-text expanding tiles\n'
    '        versionCode 503\n'
    '        versionName "3.30.75"\n',
    "3.30.75 version bump",
)
build_path.write_text(build)

# 2. Preference values. Default stays auto-scroll so existing users retain current behaviour.
arrays_path = Path("app/src/main/res/values/arrays.xml")
arrays = arrays_path.read_text()
layout_arrays = '''    <string-array name="smartHistoryLayoutValues" translatable="false">
        <item>vertical</item>
        <item>vertical_cards</item>
        <item>wheel_3d</item>
        <item>horizontal_icons</item>
        <item>horizontal_cards</item>
        <item>horizontal_names</item>
        <item>square_u</item>
    </string-array>
'''
text_arrays = layout_arrays + '''    <string-array name="smartTextOverflowEntries" translatable="false">
        <item>Auto-scroll text (compact tiles)</item>
        <item>Auto-expand tiles (show 100% of text)</item>
    </string-array>
    <string-array name="smartTextOverflowValues" translatable="false">
        <item>auto_scroll</item>
        <item>auto_expand</item>
    </string-array>
'''
arrays = replace_once(arrays, layout_arrays, text_arrays, "text overflow arrays")
arrays_path.write_text(arrays)

# 3. Put the choice beside the history layout selector in Settings.
prefs_path = Path("app/src/main/res/xml/preferences.xml")
prefs = prefs_path.read_text()
layout_pref = '''        <ListPreference
            app:defaultValue="vertical"
            app:entries="@array/smartHistoryLayoutEntries"
            app:entryValues="@array/smartHistoryLayoutValues"
            app:key="smart-history-layout"
            app:title="App history layout"
            app:useSimpleSummaryProvider="true" />
'''
overflow_pref = layout_pref + '''        <ListPreference
            app:defaultValue="auto_scroll"
            app:entries="@array/smartTextOverflowEntries"
            app:entryValues="@array/smartTextOverflowValues"
            app:key="smart-text-overflow-mode"
            app:title="Long text display (Vertical list &amp; cards)"
            app:useSimpleSummaryProvider="true" />
'''
prefs = replace_once(prefs, layout_pref, overflow_pref, "text overflow preference")
prefs_path.write_text(prefs)

# 4. Rebuild launcher UI after this setting changes so all recycled/fixed-height views switch mode.
settings_path = Path("app/src/main/java/fr/neamar/kiss/SettingsActivity.java")
settings = settings_path.read_text()
settings = replace_once(
    settings,
    '            "theme-bar-color", "results-size", "large-result-list-margins", "themed-icons", "icons-hide",\n',
    '            "theme-bar-color", "results-size", "smart-text-overflow-mode", "large-result-list-margins", "themed-icons", "icons-hide",\n',
    "Settings restart key",
)
settings_path.write_text(settings)

# 5. One shared mode policy. It is intentionally limited to Vertical list + Vertical cards.
mode_path = Path("app/src/main/java/fr/neamar/kiss/ui/TextOverflowMode.java")
if mode_path.exists():
    raise SystemExit("TextOverflowMode.java already exists on the exact baseline")
mode_path.write_text('''package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

/** Shared long-text policy for Smart S Vertical list and Vertical cards result renderers. */
public final class TextOverflowMode {
    public static final String PREF_KEY = "smart-text-overflow-mode";
    public static final String AUTO_SCROLL = "auto_scroll";
    public static final String AUTO_EXPAND = "auto_expand";
    private static final String HISTORY_LAYOUT_KEY = "smart-history-layout";

    private TextOverflowMode() { }

    @NonNull
    public static String effectiveMode(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String mode = readString(prefs, PREF_KEY, AUTO_SCROLL);
        String layout = readString(prefs, HISTORY_LAYOUT_KEY, "vertical");
        return shouldExpand(mode, layout) ? AUTO_EXPAND : AUTO_SCROLL;
    }

    public static boolean isAutoExpandForHistory(@NonNull Context context) {
        return AUTO_EXPAND.equals(effectiveMode(context));
    }

    static boolean shouldExpand(String mode, String layout) {
        return AUTO_EXPAND.equals(normalizeMode(mode)) && isSupportedLayout(layout);
    }

    static String normalizeMode(String mode) {
        return AUTO_EXPAND.equals(mode) ? AUTO_EXPAND : AUTO_SCROLL;
    }

    static boolean isSupportedLayout(String layout) {
        return "vertical".equals(layout) || "vertical_cards".equals(layout);
    }

    private static String readString(SharedPreferences prefs, String key, String fallback) {
        try {
            String value = prefs.getString(key, fallback);
            return value == null ? fallback : value;
        } catch (ClassCastException ignored) {
            return fallback;
        }
    }
}
''')

# 6. Make the one-line marquee widget mode-aware. Expand mode has no marquee timer/selection loop.
marquee_path = Path("app/src/main/java/fr/neamar/kiss/ui/AutoMarqueeTextView.java")
marquee = marquee_path.read_text()
if "Single-line text that continuously scrolls" not in marquee or "private boolean behaviorLocked;" not in marquee:
    raise SystemExit("AutoMarqueeTextView baseline did not match 3.30.74")
marquee_path.write_text('''package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

import fr.neamar.kiss.R;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

/**
 * Long-text view shared by Smart S result layouts.
 * Auto-scroll preserves the compact one-line marquee. Auto-expand disables marquee work and lets
 * the text wrap to unlimited lines so its parent tile can grow until the complete text is visible.
 */
public class AutoMarqueeTextView extends AppCompatTextView {
    private boolean behaviorLocked;

    public AutoMarqueeTextView(Context context) {
        super(context);
        init();
    }

    public AutoMarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AutoMarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        behaviorLocked = false;
        setFocusable(false);
        setFocusableInTouchMode(false);
        behaviorLocked = true;
        applyConfiguredBehavior();
    }

    private boolean isAutoExpand() {
        return TextOverflowMode.isAutoExpandForHistory(getContext());
    }

    @Override
    public void setSingleLine(boolean singleLine) {
        if (behaviorLocked) {
            applyConfiguredBehavior();
            return;
        }
        super.setSingleLine(singleLine);
    }

    @Override
    public void setMaxLines(int maxLines) {
        if (behaviorLocked) {
            super.setMaxLines(isAutoExpand() ? Integer.MAX_VALUE : 1);
            return;
        }
        super.setMaxLines(maxLines);
    }

    @Override
    public void setEllipsize(TextUtils.TruncateAt where) {
        if (behaviorLocked) {
            super.setEllipsize(isAutoExpand() ? null : TextUtils.TruncateAt.MARQUEE);
            return;
        }
        super.setEllipsize(where);
    }

    @Override
    public void setHorizontallyScrolling(boolean whether) {
        if (behaviorLocked) {
            super.setHorizontallyScrolling(!isAutoExpand());
            return;
        }
        super.setHorizontallyScrolling(whether);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applySearchAppearanceIfNeeded();
        applyConfiguredBehavior();
        if (isAutoExpand()) requestLayout();
        else restartMarquee();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        if (!isAttachedToWindow()) return;
        if (isAutoExpand()) requestLayout();
        else post(this::restartMarquee);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (isAttachedToWindow() && width != oldWidth && !isAutoExpand()) {
            post(this::restartMarquee);
        }
    }

    private void applySearchAppearanceIfNeeded() {
        if (SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY) return;
        int id = getId();
        if (id == R.id.item_app_tag
                || id == R.id.item_shortcut_tag
                || id == R.id.item_contact_phone
                || id == R.id.item_contact_nickname
                || id == R.id.item_notification_text
                || id == R.id.item_notification_title) {
            SmartTextAppearance.applySearchBody(this);
        } else {
            SmartTextAppearance.applySearchTitle(this);
        }
    }

    private void applyConfiguredBehavior() {
        if (!behaviorLocked) return;
        if (isAutoExpand()) {
            super.setSingleLine(false);
            super.setMaxLines(Integer.MAX_VALUE);
            super.setEllipsize(null);
            super.setHorizontallyScrolling(false);
            setMarqueeRepeatLimit(0);
            setHorizontalFadingEdgeEnabled(false);
            setSelected(false);
            return;
        }
        super.setSingleLine(true);
        super.setMaxLines(1);
        super.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        setMarqueeRepeatLimit(-1);
        super.setHorizontallyScrolling(true);
        setHorizontalFadingEdgeEnabled(true);
        setSelected(true);
    }

    private void restartMarquee() {
        applyConfiguredBehavior();
        if (isAutoExpand()) return;
        setSelected(false);
        setSelected(true);
        invalidate();
    }

    @Override
    public boolean isFocused() {
        return isAutoExpand() ? super.isFocused() : isShown() && hasWindowFocus();
    }

    @Override
    public boolean isSelected() {
        return isAutoExpand() ? super.isSelected() : isShown() || super.isSelected();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (isShown() && !isAutoExpand()) restartMarquee();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && !isAutoExpand()) restartMarquee();
    }
}
''')

# 7. Notification/message preview: expand mode measures its complete layout and schedules no steps.
preview_path = Path("app/src/main/java/fr/neamar/kiss/ui/AutoScrollPreviewTextView.java")
preview = preview_path.read_text()
if "private static final long STEP_DELAY_MS = 2400L;" not in preview or "private final Runnable scrollStep" not in preview:
    raise SystemExit("AutoScrollPreviewTextView baseline did not match 3.30.74")
preview_path.write_text('''package fr.neamar.kiss.ui;

import android.content.Context;
import android.text.Layout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * Notification/message preview shared by Smart S result tiles.
 * Auto-scroll keeps the compact two-line stepping preview. Auto-expand removes the timer and height
 * cap so every laid-out line contributes to the tile height and the complete text stays visible.
 */
public class AutoScrollPreviewTextView extends AppCompatTextView {
    private static final int VISIBLE_LINES = 2;
    private static final long STEP_DELAY_MS = 2400L;
    private static final long RESET_DELAY_MS = 3200L;

    private int firstVisibleLine;
    private boolean attached;
    private boolean behaviorLocked;

    private final Runnable scrollStep = this::advancePreview;

    public AutoScrollPreviewTextView(Context context) {
        super(context);
        init();
    }

    public AutoScrollPreviewTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AutoScrollPreviewTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        behaviorLocked = false;
        super.setSingleLine(false);
        super.setMaxLines(Integer.MAX_VALUE);
        super.setHorizontallyScrolling(false);
        super.setEllipsize(null);
        setFocusable(false);
        setFocusableInTouchMode(false);
        behaviorLocked = true;
        applyConfiguredBehavior();
    }

    private boolean isAutoExpand() {
        return TextOverflowMode.isAutoExpandForHistory(getContext());
    }

    @Override
    public void setSingleLine(boolean singleLine) {
        if (behaviorLocked) {
            super.setSingleLine(false);
            super.setMaxLines(Integer.MAX_VALUE);
            return;
        }
        super.setSingleLine(singleLine);
    }

    @Override
    public void setMaxLines(int maxLines) {
        super.setMaxLines(behaviorLocked ? Integer.MAX_VALUE : maxLines);
    }

    @Override
    public void setEllipsize(TextUtils.TruncateAt where) {
        super.setEllipsize(behaviorLocked ? null : where);
    }

    @Override
    public void setHorizontallyScrolling(boolean whether) {
        super.setHorizontallyScrolling(behaviorLocked ? false : whether);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        SmartTextAppearance.applySearchBody(this);
        applyConfiguredBehavior();
        if (isAutoExpand()) {
            removeCallbacks(scrollStep);
            firstVisibleLine = 0;
            scrollTo(0, 0);
            requestLayout();
        } else {
            post(this::restartAutoScroll);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(scrollStep);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        removeCallbacks(scrollStep);
        firstVisibleLine = 0;
        scrollTo(0, 0);
        if (!attached) return;
        if (isAutoExpand()) requestLayout();
        else post(this::restartAutoScroll);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (attached && (width != oldWidth || height != oldHeight) && !isAutoExpand()) {
            post(this::restartAutoScroll);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        applyConfiguredBehavior();
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (isAutoExpand()) return;

        Layout layout = getLayout();
        int contentHeight;
        if (layout != null && layout.getLineCount() > 0) {
            int visibleLineCount = Math.min(VISIBLE_LINES, layout.getLineCount());
            contentHeight = layout.getLineBottom(visibleLineCount - 1);
        } else {
            contentHeight = getLineHeight() * VISIBLE_LINES;
        }
        int cappedHeight = getCompoundPaddingTop() + getCompoundPaddingBottom() + contentHeight;
        int desiredHeight = Math.min(getMeasuredHeight(), cappedHeight);
        int mode = View.MeasureSpec.getMode(heightMeasureSpec);
        int size = View.MeasureSpec.getSize(heightMeasureSpec);
        if (mode == View.MeasureSpec.EXACTLY) desiredHeight = size;
        else if (mode == View.MeasureSpec.AT_MOST) desiredHeight = Math.min(desiredHeight, size);

        setMeasuredDimension(getMeasuredWidth(), desiredHeight);
    }

    private void applyConfiguredBehavior() {
        if (!behaviorLocked) return;
        super.setSingleLine(false);
        super.setMaxLines(Integer.MAX_VALUE);
        super.setHorizontallyScrolling(false);
        super.setEllipsize(null);
        setHorizontalFadingEdgeEnabled(false);
        setVerticalFadingEdgeEnabled(!isAutoExpand());
        if (!isAutoExpand()) setFadingEdgeLength(dp(8));
    }

    private void restartAutoScroll() {
        removeCallbacks(scrollStep);
        applyConfiguredBehavior();
        firstVisibleLine = 0;
        scrollTo(0, 0);
        if (attached && !isAutoExpand()) postDelayed(scrollStep, STEP_DELAY_MS);
    }

    private void advancePreview() {
        if (!attached || isAutoExpand()) return;
        Layout layout = getLayout();
        if (layout == null) {
            postDelayed(scrollStep, STEP_DELAY_MS);
            return;
        }

        int lineCount = layout.getLineCount();
        if (lineCount <= VISIBLE_LINES) {
            firstVisibleLine = 0;
            scrollTo(0, 0);
            return;
        }

        int maxFirstLine = lineCount - VISIBLE_LINES;
        if (firstVisibleLine >= maxFirstLine) {
            firstVisibleLine = 0;
            scrollTo(0, 0);
            postDelayed(scrollStep, RESET_DELAY_MS);
            return;
        }

        firstVisibleLine++;
        int visibleTextHeight = Math.max(0,
                getHeight() - getCompoundPaddingTop() - getCompoundPaddingBottom());
        int maxScroll = Math.max(0, layout.getHeight() - visibleTextHeight);
        int target = Math.min(layout.getLineTop(firstVisibleLine), maxScroll);
        scrollTo(0, target);
        postDelayed(scrollStep, STEP_DELAY_MS);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
''')

# 8. Vertical List: make every non-button result label obey the selected mode and never END-ellipsis
# in auto-expand. Also key the recycle cache/signature by effective overflow mode.
adapter_path = Path("app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java")
adapter = adapter_path.read_text()
adapter = replace_once(
    adapter,
    "import fr.neamar.kiss.ui.NotificationBellStyle;\nimport fr.neamar.kiss.ui.TileVisualStyle;\n",
    "import fr.neamar.kiss.ui.NotificationBellStyle;\nimport fr.neamar.kiss.ui.TextOverflowMode;\nimport fr.neamar.kiss.ui.TileVisualStyle;\n",
    "RecordAdapter TextOverflowMode import",
)
adapter = replace_once(
    adapter,
    "    private final WeakHashMap<View, Boolean> overflowConfigured = new WeakHashMap<>();\n",
    "    private final WeakHashMap<View, String> overflowConfigured = new WeakHashMap<>();\n",
    "RecordAdapter overflow cache type",
)
adapter = replace_once(
    adapter,
    '''        if (!overflowConfigured.containsKey(view)) {
            configureOverflowText(view);
            overflowConfigured.put(view, Boolean.TRUE);
        }
''',
    '''        String overflowMode = TextOverflowMode.effectiveMode(parent.getContext());
        if (!TextUtils.equals(overflowConfigured.get(view), overflowMode)) {
            configureOverflowText(view);
            overflowConfigured.put(view, overflowMode);
        }
''',
    "RecordAdapter mode-aware recycle cache",
)
old_overflow = '''    private void configureOverflowText(View view) {
        if (view instanceof TextView && !(view instanceof Button)) {
            TextView text = (TextView) view;
            if (text.getId() == R.id.item_communication_body) {
                text.setSingleLine(false);
                text.setMaxLines(Integer.MAX_VALUE);
                text.setHorizontallyScrolling(false);
                text.setEllipsize(null);
                text.setHorizontalFadingEdgeEnabled(false);
                return;
            }
            configureMarquee(text);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) configureOverflowText(group.getChildAt(i));
    }

    private void configureMarquee(TextView text) {
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
'''
new_overflow = '''    private void configureOverflowText(View view) {
        if (view instanceof TextView && !(view instanceof Button)) {
            TextView text = (TextView) view;
            if (TextOverflowMode.isAutoExpandForHistory(view.getContext())) {
                configureExpandedText(text);
                return;
            }
            if (text.getId() == R.id.item_communication_body) {
                text.setSingleLine(false);
                text.setMaxLines(Integer.MAX_VALUE);
                text.setHorizontallyScrolling(false);
                text.setEllipsize(null);
                text.setHorizontalFadingEdgeEnabled(false);
                text.setSelected(false);
                return;
            }
            configureMarquee(text);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) configureOverflowText(group.getChildAt(i));
    }

    private void configureMarquee(TextView text) {
        if (TextOverflowMode.isAutoExpandForHistory(text.getContext())) {
            configureExpandedText(text);
            return;
        }
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

    private void configureExpandedText(TextView text) {
        text.setSelected(false);
        text.setSingleLine(false);
        text.setMaxLines(Integer.MAX_VALUE);
        text.setHorizontallyScrolling(false);
        text.setEllipsize(null);
        text.setHorizontalFadingEdgeEnabled(false);
        text.setFocusable(false);
        text.setFocusableInTouchMode(false);
        makeTextUseAvailableWidth(text);
    }
'''
adapter = replace_once(adapter, old_overflow, new_overflow, "RecordAdapter overflow policy")
old_body_lines = '''    private void configureVerticalHistoryBodyLines(View row, int[] ids, int lines) {
        for (int id : ids) {
            View candidate = row.findViewById(id);
            if (!(candidate instanceof TextView) || candidate.getVisibility() == View.GONE) continue;
            TextView text = (TextView) candidate;
            if (lines <= 1 && id != R.id.item_communication_body) {
                configureMarquee(text);
                continue;
            }
            text.setSingleLine(false);
            text.setMaxLines(Math.max(2, lines));
            text.setHorizontallyScrolling(false);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setHorizontalFadingEdgeEnabled(false);
            text.setSelected(false);
        }
    }
'''
new_body_lines = '''    private void configureVerticalHistoryBodyLines(View row, int[] ids, int lines) {
        if (TextOverflowMode.isAutoExpandForHistory(row.getContext())) {
            for (int id : ids) {
                View candidate = row.findViewById(id);
                if (!(candidate instanceof TextView) || candidate.getVisibility() == View.GONE) continue;
                configureExpandedText((TextView) candidate);
            }
            return;
        }
        for (int id : ids) {
            View candidate = row.findViewById(id);
            if (!(candidate instanceof TextView) || candidate.getVisibility() == View.GONE) continue;
            TextView text = (TextView) candidate;
            if (lines <= 1 && id != R.id.item_communication_body) {
                configureMarquee(text);
                continue;
            }
            text.setSingleLine(false);
            text.setMaxLines(Math.max(2, lines));
            text.setHorizontallyScrolling(false);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setHorizontalFadingEdgeEnabled(false);
            text.setSelected(false);
        }
    }
'''
adapter = replace_once(adapter, old_body_lines, new_body_lines, "Vertical list full-text body policy")
adapter = replace_once(
    adapter,
    '        result = 31 * result + safePercent(prefs, "smart-list-row-spacing-dp", 4, 0, 96);\n',
    '        result = 31 * result + safePercent(prefs, "smart-list-row-spacing-dp", 4, 0, 96);\n'
    '        result = 31 * result + TextOverflowMode.effectiveMode(context).hashCode();\n',
    "Vertical list style signature overflow mode",
)
adapter_path.write_text(adapter)

# 9. Vertical Cards: release fixed text-row heights in expand mode and never require a tap to reveal
# text that the selected mode promises to show immediately.
card_path = Path("app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java")
card = card_path.read_text()
card = replace_once(
    card,
    "import fr.neamar.kiss.ui.SmartAnimationEngine;\n",
    "import fr.neamar.kiss.ui.SmartAnimationEngine;\nimport fr.neamar.kiss.ui.TextOverflowMode;\n",
    "SmartCard TextOverflowMode import",
)
height_replacements = [
    ("ViewGroup.LayoutParams.MATCH_PARENT, dp(31) * Math.max(90, namePercent) / 100));",
     "ViewGroup.LayoutParams.MATCH_PARENT, textRowHeight(dp(31) * Math.max(90, namePercent) / 100)));",
     "card title height"),
    ("ViewGroup.LayoutParams.MATCH_PARENT, dp(27)));",
     "ViewGroup.LayoutParams.MATCH_PARENT, textRowHeight(dp(27))));",
     "card subtitle height"),
    ("ViewGroup.LayoutParams.MATCH_PARENT, dp(31)));",
     "ViewGroup.LayoutParams.MATCH_PARENT, textRowHeight(dp(31))));",
     "card message height"),
    ("ViewGroup.LayoutParams.MATCH_PARENT, dp(25)));",
     "ViewGroup.LayoutParams.MATCH_PARENT, textRowHeight(dp(25))));",
     "card context height"),
]
for old, new, label in height_replacements:
    card = replace_once(card, old, new, label)
# The title-sized fixed-height expression appears twice (title + caller name); first replacement above
# intentionally changed exactly one occurrence, so change the remaining caller-name occurrence now.
card = replace_once(
    card,
    "ViewGroup.LayoutParams.MATCH_PARENT, dp(31) * Math.max(90, namePercent) / 100));",
    "ViewGroup.LayoutParams.MATCH_PARENT, textRowHeight(dp(31) * Math.max(90, namePercent) / 100)));",
    "caller name height",
)
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

    private boolean messageNeedsExpansion(TextView text) {
        CharSequence value = text.getText();
'''
new_collapsed = '''    private int textRowHeight(int scrollingHeight) {
        return TextOverflowMode.isAutoExpandForHistory(mainActivity)
                ? ViewGroup.LayoutParams.WRAP_CONTENT : scrollingHeight;
    }

    private void configureCollapsedMessage(TextView text) {
        if (TextOverflowMode.isAutoExpandForHistory(mainActivity)) {
            text.setSelected(false);
            text.setHorizontallyScrolling(false);
            text.setSingleLine(false);
            text.setMaxLines(Integer.MAX_VALUE);
            text.setEllipsize(null);
            text.setHorizontalFadingEdgeEnabled(false);
        } else {
            text.setSingleLine(true);
            text.setMaxLines(1);
            text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            text.setMarqueeRepeatLimit(-1);
            text.setHorizontallyScrolling(true);
            text.setSelected(true);
        }
        text.setTextColor(Color.WHITE);
        text.setTextSize(13f);
        text.setGravity(Gravity.START);
        text.setPadding(0, dp(2), 0, dp(2));
    }

    private boolean messageNeedsExpansion(TextView text) {
        if (TextOverflowMode.isAutoExpandForHistory(mainActivity)) return false;
        CharSequence value = text.getText();
'''
card = replace_once(card, old_collapsed, new_collapsed, "Vertical Card collapsed message policy")
card_path.write_text(card)

# 10. Pure policy regression tests: scope stays exactly Vertical list + Vertical cards and defaults safe.
test_path = Path("app/src/test/java/fr/neamar/kiss/ui/TextOverflowModeTest.java")
if test_path.exists():
    raise SystemExit("TextOverflowModeTest.java already exists on baseline")
test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text('''package fr.neamar.kiss.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextOverflowModeTest {
    @Test
    void autoExpandAppliesToBothRequestedVerticalLayouts() {
        assertTrue(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "vertical"));
        assertTrue(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "vertical_cards"));
    }

    @Test
    void autoExpandDoesNotLeakIntoOtherHistoryRenderers() {
        assertFalse(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "wheel_3d"));
        assertFalse(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "horizontal_icons"));
        assertFalse(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "horizontal_cards"));
        assertFalse(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "horizontal_names"));
        assertFalse(TextOverflowMode.shouldExpand(TextOverflowMode.AUTO_EXPAND, "square_u"));
    }

    @Test
    void unknownOrMissingModeFallsBackToExistingAutoScrollBehaviour() {
        assertEquals(TextOverflowMode.AUTO_SCROLL, TextOverflowMode.normalizeMode(null));
        assertEquals(TextOverflowMode.AUTO_SCROLL, TextOverflowMode.normalizeMode("unexpected"));
        assertFalse(TextOverflowMode.shouldExpand(null, "vertical"));
        assertFalse(TextOverflowMode.shouldExpand("unexpected", "vertical_cards"));
    }
}
''')

print("3.30.75 text overflow modes applied with exact 3.30.74 source assertions")
