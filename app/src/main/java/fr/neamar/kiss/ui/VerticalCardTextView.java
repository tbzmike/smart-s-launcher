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
}
