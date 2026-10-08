package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

/**
 * Launcher-owned QWERTY keyboard used by {@link SearchEditText}.
 *
 * <p>This is intentionally a normal view rather than an Android IME. Its lifecycle and visibility
 * are therefore controlled by Smart S itself and are not affected by IME inset/focus changes.</p>
 */
public final class BuiltInQwertyKeyboard extends LinearLayout {
    private static final int KEY_GAP_DP = 2;
    private static final int PREVIEW_SIZE_DP = 58;

    private final SearchEditText target;
    private boolean shifted;
    private PopupWindow previewWindow;
    private int buttonSizePercent = BuiltInKeyboardSizing.DEFAULT_BUTTON_PERCENT;
    private int labelSizeSp = BuiltInKeyboardSizing.DEFAULT_LABEL_SIZE_SP;

    public BuiltInQwertyKeyboard(@NonNull Context context, @NonNull SearchEditText target) {
        super(context);
        this.target = target;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(3), dp(4), dp(3), dp(4));
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        readSizingPreferences();
        buildKeys();
    }

    public void refreshSizingFromPreferences() {
        int previousButtonSize = buttonSizePercent;
        int previousLabelSize = labelSizeSp;
        readSizingPreferences();

        if (previousButtonSize != buttonSizePercent || previousLabelSize != labelSizeSp) {
            dismissPreview();
            buildKeys();
        } else {
            requestLayout();
            invalidate();
        }
    }

    private void readSizingPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        buttonSizePercent = BuiltInKeyboardSizing.clampButtonPercent(
                prefs.getInt(BuiltInKeyboardSizing.PREF_BUTTON_PERCENT,
                        BuiltInKeyboardSizing.DEFAULT_BUTTON_PERCENT));
        labelSizeSp = BuiltInKeyboardSizing.clampLabelSizeSp(
                prefs.getInt(BuiltInKeyboardSizing.PREF_LABEL_SIZE_SP,
                        BuiltInKeyboardSizing.DEFAULT_LABEL_SIZE_SP));
    }

    private void buildKeys() {
        removeAllViews();
        addCharacterRow("1234567890", 1f);
        addCharacterRow("qwertyuiop", 1f);
        addCharacterRow("asdfghjkl", 1f);

        LinearLayout fourth = newRow();
        addSpecialKey(fourth, shifted ? "⇧" : "⇧", 1.45f, () -> {
            shifted = !shifted;
            buildKeys();
        });
        for (char c : "zxcvbnm".toCharArray()) {
            addCharacterKey(fourth, String.valueOf(c), 1f);
        }
        addSpecialKey(fourth, "⌫", 1.45f, target::deleteBeforeCursor);
        addView(fourth);

        LinearLayout bottom = newRow();
        addSpecialKey(bottom, "⌨", 1.1f, target::showInstalledKeyboardPicker);
        addSpecialKey(bottom, "▾", 1.1f, target::hideBuiltInKeyboard);
        addCharacterKey(bottom, ",", 0.85f);
        addSpecialKey(bottom, "space", 4.35f, () -> target.commitFromBuiltInKeyboard(" "));
        addCharacterKey(bottom, ".", 0.85f);
        addSpecialKey(bottom, "↵", 1.25f, target::performBuiltInEditorAction);
        addView(bottom);
    }

    private void addCharacterRow(String chars, float weight) {
        LinearLayout row = newRow();
        for (char c : chars.toCharArray()) {
            addCharacterKey(row, String.valueOf(c), weight);
        }
        addView(row);
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        // Five rows divide the configured keyboard height equally. The outer keyboard height is
        // controlled independently by SearchEditText and can grow up to half the screen.
        row.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        return row;
    }

    private void addCharacterKey(LinearLayout row, String value, float weight) {
        final String shown = shifted && value.length() == 1 && Character.isLetter(value.charAt(0))
                ? value.toUpperCase()
                : value;
        addKey(row, shown, weight, true, () -> {
            target.commitFromBuiltInKeyboard(shown);
            if (shifted && shown.length() == 1 && Character.isLetter(shown.charAt(0))) {
                shifted = false;
                buildKeys();
            }
        });
    }

    private void addSpecialKey(LinearLayout row, String label, float weight, Runnable action) {
        addKey(row, label, weight, false, action);
    }

    private void addKey(LinearLayout row, String label, float weight, boolean showPreview, Runnable action) {
        TextView key = new TextView(getContext());
        key.setText(label);
        key.setTextSize(labelSizeSp);
        key.setGravity(Gravity.CENTER);
        key.setClickable(true);
        key.setFocusable(false);
        key.setMinWidth(0);
        key.setMinHeight(0);

        GradientDrawable background = new GradientDrawable();
        background.setColor(resolveSurfaceColor());
        background.setCornerRadius(dp(6));
        key.setBackground(background);

        LayoutParams params = new LayoutParams(0, LayoutParams.MATCH_PARENT, weight);
        int keyInset = dp(KEY_GAP_DP
                + BuiltInKeyboardSizing.extraButtonInsetDp(buttonSizePercent));
        params.setMargins(keyInset, keyInset, keyInset, keyInset);
        row.addView(key, params);

        key.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (showPreview) {
                        showPreview(key, label);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    dismissPreview();
                    Rect hit = new Rect();
                    v.getHitRect(hit);
                    if (event.getX() >= 0 && event.getX() < v.getWidth()
                            && event.getY() >= 0 && event.getY() < v.getHeight()) {
                        action.run();
                    }
                    v.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dismissPreview();
                    return true;
                default:
                    return true;
            }
        });
    }

    private int resolveSurfaceColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.colorBackgroundFloating, value, true)) {
            return value.data;
        }
        return 0xffeeeeee;
    }

    private void showPreview(View anchor, String text) {
        dismissPreview();

        TextView preview = new TextView(getContext());
        preview.setText(text);
        preview.setTextSize(Math.min(52f, labelSizeSp * 1.55f));
        preview.setGravity(Gravity.CENTER);

        GradientDrawable background = new GradientDrawable();
        background.setColor(resolveSurfaceColor());
        background.setCornerRadius(dp(9));
        preview.setBackground(background);
        preview.setElevation(dp(8));

        int previewSizeDp = Math.max(PREVIEW_SIZE_DP,
                Math.min(104, Math.round(labelSizeSp * 3.2f)));
        previewWindow = new PopupWindow(preview, dp(previewSizeDp), dp(previewSizeDp), false);
        previewWindow.setClippingEnabled(false);
        previewWindow.setOutsideTouchable(false);
        previewWindow.setTouchable(false);
        previewWindow.setElevation(dp(8));

        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int previewPx = dp(previewSizeDp);
        int x = location[0] + (anchor.getWidth() - previewPx) / 2;
        int y = location[1] - previewPx - dp(6);
        previewWindow.showAtLocation(anchor.getRootView(), Gravity.NO_GRAVITY, x, y);
    }

    private void dismissPreview() {
        if (previewWindow != null) {
            previewWindow.dismiss();
            previewWindow = null;
        }
    }

    public void release() {
        dismissPreview();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
