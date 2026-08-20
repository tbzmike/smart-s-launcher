package fr.neamar.kiss.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * TextView that scrolls overflowing single-line text while the launcher window is active.
 *
 * AbsListView/Recycler-style parents can clear a child's selected state during row selection
 * changes, which silently stops the standard Android marquee. Returning window-focus state from
 * isFocused() keeps the marquee alive while the launcher is in the foreground without keeping
 * animation work running after the launcher loses window focus.
 */
public class AutoMarqueeTextView extends AppCompatTextView {
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
        setSingleLine(true);
        setMaxLines(1);
        setEllipsize(TextUtils.TruncateAt.MARQUEE);
        setMarqueeRepeatLimit(-1);
        setHorizontallyScrolling(true);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    @Override
    public boolean isFocused() {
        return isShown() && hasWindowFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            // Re-evaluate marquee after returning to the launcher.
            requestLayout();
            invalidate();
        }
    }
}
