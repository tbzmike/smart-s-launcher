package fr.neamar.kiss.ui;

import android.content.Context;
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

/**
 * Launcher-owned QWERTY keyboard used by {@link SearchEditText}.
 *
 * <p>This is intentionally a normal view rather than an Android IME. Its lifecycle and visibility
 * are therefore controlled by Smart S itself and are not affected by IME inset/focus changes.</p>
 */
public final class BuiltInQwertyKeyboard extends LinearLayout {
    private static final int KEY_HEIGHT_DP = 48;
    private static final int KEY_GAP_DP = 2;
    private static final int PREVIEW_SIZE_DP = 58;

    private final SearchEditText target;
    private boolean shifted;
    private PopupWindow previewWindow;

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
        buildKeys();
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
        addSpecialKey(bottom, "▾", 1.2f, target::hideBuiltInKeyboard);
        addCharacterKey(bottom, ",", 0.9f);
        addSpecialKey(bottom, "space", 4.7f, () -> target.commitFromBuiltInKeyboard(" "));
        addCharacterKey(bottom, ".", 0.9f);
        addSpecialKey(bottom, "↵", 1.35f, target::performBuiltInEditorAction);
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
        row.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp(KEY_HEIGHT_DP)));
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
        key.setTextSize(18);
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
        params.setMargins(dp(KEY_GAP_DP), dp(KEY_GAP_DP), dp(KEY_GAP_DP), dp(KEY_GAP_DP));
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
        preview.setTextSize(28);
        preview.setGravity(Gravity.CENTER);

        GradientDrawable background = new GradientDrawable();
        background.setColor(resolveSurfaceColor());
        background.setCornerRadius(dp(9));
        preview.setBackground(background);
        preview.setElevation(dp(8));

        previewWindow = new PopupWindow(preview, dp(PREVIEW_SIZE_DP), dp(PREVIEW_SIZE_DP), false);
        previewWindow.setClippingEnabled(false);
        previewWindow.setOutsideTouchable(false);
        previewWindow.setTouchable(false);
        previewWindow.setElevation(dp(8));

        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int x = location[0] + (anchor.getWidth() - dp(PREVIEW_SIZE_DP)) / 2;
        int y = location[1] - dp(PREVIEW_SIZE_DP) - dp(6);
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
