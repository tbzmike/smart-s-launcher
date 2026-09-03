package fr.neamar.kiss.ui;

import android.content.Context;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * Text used only by Vertical Cards. It uses all available card width and height first: text wraps
 * naturally across multiple lines when the complete value fits. Marquee is enabled only when the
 * complete value cannot fit in the measured text area even after wrapping.
 */
public class VerticalCardTextView extends AppCompatTextView {
    private boolean applyingFit;
    private int lineBudget = 2;

    public VerticalCardTextView(Context context) {
        super(context);
        init();
    }

    public VerticalCardTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VerticalCardTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setFocusable(false);
        setFocusableInTouchMode(false);
        setHorizontalFadingEdgeEnabled(true);
    }

    public void setLineBudget(int lines) {
        lineBudget = Math.max(2, Math.min(4, lines));
        if (isAttachedToWindow()) post(this::applyBestFitMode);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::applyBestFitMode);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        if (!applyingFit && isAttachedToWindow()) post(this::applyBestFitMode);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (!applyingFit && (width != oldWidth || height != oldHeight)) post(this::applyBestFitMode);
    }

    private void applyBestFitMode() {
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
}
