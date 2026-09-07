package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.RelativeLayout;

import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.preference.PreferenceManager;

import fr.neamar.kiss.R;

public class SearchEditText extends AppCompatEditText {
    public static final String PREF_SEARCH_KEYBOARD_MODE = "search-keyboard-mode";
    public static final String KEYBOARD_MODE_BUILT_IN = "built-in";
    public static final String KEYBOARD_MODE_SYSTEM = "system";

    private OnEditorActionListener mEditorListener;
    private BuiltInQwertyKeyboard builtInKeyboard;
    private RelativeLayout keyboardRoot;
    private View searchEditLayout;
    private View externalFavoriteBar;

    public SearchEditText(Context context) {
        super(context);
        initInputMode();
    }

    public SearchEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        initInputMode();
    }

    public SearchEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initInputMode();
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(getContext());
    }

    private void initInputMode() {
        applyKeyboardMode();
    }

    public boolean isBuiltInKeyboardEnabled() {
        return KEYBOARD_MODE_BUILT_IN.equals(
                prefs().getString(PREF_SEARCH_KEYBOARD_MODE, KEYBOARD_MODE_BUILT_IN));
    }

    public boolean isBuiltInKeyboardVisible() {
        return builtInKeyboard != null && builtInKeyboard.getVisibility() == VISIBLE;
    }

    private void applyKeyboardMode() {
        if (isBuiltInKeyboardEnabled()) {
            setShowSoftInputOnFocus(false);
            hideSystemKeyboard();
        } else {
            setShowSoftInputOnFocus(true);
            if (isBuiltInKeyboardVisible()) hideBuiltInKeyboard();
        }
    }

    /** Re-read the persisted keyboard mode before focus/lifecycle transitions. */
    public void syncKeyboardMode() {
        applyKeyboardMode();
    }

    /** Built-in mode owns input completely; never leave Android IME visible behind it. */
    private void hideSystemKeyboard() {
        setShowSoftInputOnFocus(false);
        WindowInsetsControllerCompat controller = ViewCompat.getWindowInsetsController(this);
        if (controller != null) controller.hide(WindowInsetsCompat.Type.ime());
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getWindowToken() != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        installBuiltInKeyboard();
        applyKeyboardMode();
    }

    @Override
    protected void onDetachedFromWindow() {
        final BuiltInQwertyKeyboard keyboard = builtInKeyboard;
        final RelativeLayout root = keyboardRoot;

        if (keyboard != null) {
            keyboard.release();
        }

        // Do not mutate root's child array while ViewGroup.dispatchDetachedFromWindow() is
        // iterating it. Removing the keyboard synchronously from this sibling's detach callback can
        // shift/null a child slot underneath Android's traversal and crash in dispatchDetachedFromWindow().
        if (root != null && keyboard != null && keyboard.getParent() == root) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (keyboard.getParent() == root) {
                    root.removeView(keyboard);
                }
            });
        }

        builtInKeyboard = null;
        keyboardRoot = null;
        searchEditLayout = null;
        externalFavoriteBar = null;
        super.onDetachedFromWindow();
    }

    private void installBuiltInKeyboard() {
        if (builtInKeyboard != null) return;

        View parent = (View) getParent();
        if (parent == null || !(parent.getParent() instanceof RelativeLayout)) return;

        searchEditLayout = parent;
        keyboardRoot = (RelativeLayout) parent.getParent();
        externalFavoriteBar = keyboardRoot.findViewById(R.id.externalFavoriteBar);

        builtInKeyboard = new BuiltInQwertyKeyboard(getContext(), this);
        builtInKeyboard.setId(View.generateViewId());
        builtInKeyboard.setVisibility(GONE);

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_START, RelativeLayout.TRUE);
        params.addRule(RelativeLayout.ALIGN_PARENT_END, RelativeLayout.TRUE);
        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE);
        keyboardRoot.addView(builtInKeyboard, params);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            applyKeyboardMode();
            if (isBuiltInKeyboardEnabled()) showBuiltInKeyboard();
        }
        return super.onTouchEvent(event);
    }

    public void showBuiltInKeyboard() {
        if (!isBuiltInKeyboardEnabled()) return;
        // Suppress Android IME before any focus/layout work, then reassert after showing.
        hideSystemKeyboard();
        installBuiltInKeyboard();
        if (builtInKeyboard == null || searchEditLayout == null) return;

        hideSystemKeyboard();
        setCursorVisible(true);
        builtInKeyboard.setVisibility(VISIBLE);
        placeSearchAreaAboveBuiltInKeyboard(true);
        builtInKeyboard.bringToFront();
        requestLayout();
        // IME visibility changes are asynchronous; one posted hide closes any queued system show.
        post(this::hideSystemKeyboard);
    }

    public void hideBuiltInKeyboard() {
        if (builtInKeyboard == null) return;
        builtInKeyboard.setVisibility(GONE);
        placeSearchAreaAboveBuiltInKeyboard(false);
        setCursorVisible(false);
        requestLayout();
    }

    private void placeSearchAreaAboveBuiltInKeyboard(boolean visible) {
        if (searchEditLayout == null || keyboardRoot == null
                || !(searchEditLayout.getLayoutParams() instanceof RelativeLayout.LayoutParams)) return;

        RelativeLayout.LayoutParams searchParams =
                (RelativeLayout.LayoutParams) searchEditLayout.getLayoutParams();
        searchParams.removeRule(RelativeLayout.ABOVE);
        searchParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        if (visible && builtInKeyboard != null) {
            searchParams.addRule(RelativeLayout.ABOVE, builtInKeyboard.getId());
        } else {
            searchParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE);
        }
        searchEditLayout.setLayoutParams(searchParams);

        if (externalFavoriteBar != null
                && externalFavoriteBar.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams favoriteParams =
                    (RelativeLayout.LayoutParams) externalFavoriteBar.getLayoutParams();
            favoriteParams.removeRule(RelativeLayout.ABOVE);
            favoriteParams.addRule(RelativeLayout.ABOVE, R.id.searchEditLayout);
            externalFavoriteBar.setLayoutParams(favoriteParams);
        }
        keyboardRoot.requestLayout();
    }

    public void useBuiltInKeyboard() {
        prefs().edit().putString(PREF_SEARCH_KEYBOARD_MODE, KEYBOARD_MODE_BUILT_IN).apply();
        hideSystemKeyboard();
        requestFocus();
        post(this::showBuiltInKeyboard);
    }

    public void useSystemKeyboard(boolean showPicker) {
        prefs().edit().putString(PREF_SEARCH_KEYBOARD_MODE, KEYBOARD_MODE_SYSTEM).apply();
        hideBuiltInKeyboard();
        setShowSoftInputOnFocus(true);
        requestFocus();
        setCursorVisible(true);
        post(() -> {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
                if (showPicker) imm.showInputMethodPicker();
            }
        });
    }

    public void showInstalledKeyboardPicker() {
        useSystemKeyboard(true);
    }

    void commitFromBuiltInKeyboard(String text) {
        Editable editable = getText();
        if (editable == null) return;
        int start = Math.max(0, getSelectionStart());
        int end = Math.max(0, getSelectionEnd());
        int replaceStart = Math.min(start, end);
        int replaceEnd = Math.max(start, end);
        editable.replace(replaceStart, replaceEnd, text);
        setSelection(replaceStart + text.length());
    }

    void deleteBeforeCursor() {
        Editable editable = getText();
        if (editable == null || editable.length() == 0) return;
        int start = Math.max(0, getSelectionStart());
        int end = Math.max(0, getSelectionEnd());
        if (start != end) {
            int deleteStart = Math.min(start, end);
            int deleteEnd = Math.max(start, end);
            editable.delete(deleteStart, deleteEnd);
            setSelection(deleteStart);
            return;
        }
        if (start > 0) {
            int codePoint = Character.codePointBefore(editable, start);
            int deleteStart = Math.max(0, start - Character.charCount(codePoint));
            editable.delete(deleteStart, start);
            setSelection(deleteStart);
        }
    }

    void performBuiltInEditorAction() {
        if (mEditorListener != null) {
            mEditorListener.onEditorAction(this, EditorInfo.IME_ACTION_SEARCH, null);
        }
    }

    @Override
    public void setOnEditorActionListener(OnEditorActionListener listener) {
        mEditorListener = listener;
        super.setOnEditorActionListener(listener);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            if (isBuiltInKeyboardVisible()) {
                hideBuiltInKeyboard();
                return true;
            }
            if (mEditorListener != null
                    && mEditorListener.onEditorAction(this, android.R.id.closeButton, event)) return true;
        }
        return super.onKeyPreIme(keyCode, event);
    }
}
