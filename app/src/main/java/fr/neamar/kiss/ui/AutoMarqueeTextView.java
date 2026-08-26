package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

import fr.neamar.kiss.R;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

/**
 * Single-line text that continuously scrolls whenever its complete content does not fit.
 * Generic row styling is not allowed to replace this behavior with END ellipsis or clipped
 * multi-line text: labels, titles and history metadata must always remain fully readable.
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
        super.setSingleLine(true);
        super.setMaxLines(1);
        super.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        setMarqueeRepeatLimit(-1);
        super.setHorizontallyScrolling(true);
        setHorizontalFadingEdgeEnabled(true);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setSelected(true);
        behaviorLocked = true;
    }

    @Override
    public void setSingleLine(boolean singleLine) {
        if (behaviorLocked) {
            super.setSingleLine(true);
            super.setMaxLines(1);
            return;
        }
        super.setSingleLine(singleLine);
    }

    @Override
    public void setMaxLines(int maxLines) {
        super.setMaxLines(behaviorLocked ? 1 : maxLines);
    }

    @Override
    public void setEllipsize(TextUtils.TruncateAt where) {
        super.setEllipsize(behaviorLocked ? TextUtils.TruncateAt.MARQUEE : where);
    }

    @Override
    public void setHorizontallyScrolling(boolean whether) {
        super.setHorizontallyScrolling(behaviorLocked || whether);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applySearchAppearanceIfNeeded();
        restoreMarqueeBehavior();
        restartMarquee();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        if (isAttachedToWindow()) post(this::restartMarquee);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (isAttachedToWindow() && width != oldWidth) post(this::restartMarquee);
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

    private void restoreMarqueeBehavior() {
        if (!behaviorLocked) return;
        super.setSingleLine(true);
        super.setMaxLines(1);
        super.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        setMarqueeRepeatLimit(-1);
        super.setHorizontallyScrolling(true);
        setHorizontalFadingEdgeEnabled(true);
    }

    private void restartMarquee() {
        restoreMarqueeBehavior();
        setSelected(false);
        setSelected(true);
        invalidate();
    }

    @Override
    public boolean isFocused() {
        return isShown() && hasWindowFocus();
    }

    @Override
    public boolean isSelected() {
        return isShown() || super.isSelected();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (isShown()) restartMarquee();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) restartMarquee();
    }
}
