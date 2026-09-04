package fr.neamar.kiss.ui;

import android.content.Context;
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

import fr.neamar.kiss.R;

public class SearchEditText extends AppCompatEditText {
    private OnEditorActionListener mEditorListener;
    private BuiltInQwertyKeyboard builtInKeyboard;
    private RelativeLayout keyboardRoot;
    private View externalFavoriteBar;

    public SearchEditText(Context context) {
        super(context);
        initBuiltInInput();
    }

    public SearchEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        initBuiltInInput();
    }

    public SearchEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initBuiltInInput();
    }

    private void initBuiltInInput() {
        // Smart S owns its search keyboard. Do not let Android summon a separate IME for taps.
        setShowSoftInputOnFocus(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        installBuiltInKeyboard();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (builtInKeyboard != null) {
            builtInKeyboard.release();
        }
        if (keyboardRoot != null && builtInKeyboard != null && builtInKeyboard.getParent() == keyboardRoot) {
            keyboardRoot.removeView(builtInKeyboard);
        }
        builtInKeyboard = null;
        keyboardRoot = null;
        externalFavoriteBar = null;
        super.onDetachedFromWindow();
    }

    private void installBuiltInKeyboard() {
        if (builtInKeyboard != null) {
            return;
        }

        View parent = (View) getParent();
        if (parent == null || !(parent.getParent() instanceof RelativeLayout)) {
            return;
        }

        keyboardRoot = (RelativeLayout) parent.getParent();
        externalFavoriteBar = keyboardRoot.findViewById(R.id.externalFavoriteBar);

        builtInKeyboard = new BuiltInQwertyKeyboard(getContext(), this);
        builtInKeyboard.setId(View.generateViewId());
        builtInKeyboard.setVisibility(GONE);

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.addRule(RelativeLayout.ABOVE, R.id.searchEditLayout);
        params.addRule(RelativeLayout.ALIGN_PARENT_START, RelativeLayout.TRUE);
        params.addRule(RelativeLayout.ALIGN_PARENT_END, RelativeLayout.TRUE);
        keyboardRoot.addView(builtInKeyboard, params);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            showBuiltInKeyboard();
        }
        return super.onTouchEvent(event);
    }

    public void showBuiltInKeyboard() {
        installBuiltInKeyboard();
        if (builtInKeyboard == null) {
            return;
        }

        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }

        setCursorVisible(true);
        builtInKeyboard.setVisibility(VISIBLE);
        anchorFavoritesAboveBuiltInKeyboard(true);
        builtInKeyboard.bringToFront();
        requestLayout();
    }

    public void hideBuiltInKeyboard() {
        if (builtInKeyboard == null) {
            return;
        }
        builtInKeyboard.setVisibility(GONE);
        anchorFavoritesAboveBuiltInKeyboard(false);
        setCursorVisible(false);
        requestLayout();
    }

    private void anchorFavoritesAboveBuiltInKeyboard(boolean keyboardVisible) {
        if (externalFavoriteBar == null || !(externalFavoriteBar.getLayoutParams() instanceof RelativeLayout.LayoutParams)) {
            return;
        }
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) externalFavoriteBar.getLayoutParams();
        params.removeRule(RelativeLayout.ABOVE);
        params.addRule(RelativeLayout.ABOVE,
                keyboardVisible && builtInKeyboard != null ? builtInKeyboard.getId() : R.id.searchEditLayout);
        externalFavoriteBar.setLayoutParams(params);
    }

    void commitFromBuiltInKeyboard(String text) {
        Editable editable = getText();
        if (editable == null) {
            return;
        }
        int start = Math.max(0, getSelectionStart());
        int end = Math.max(0, getSelectionEnd());
        int replaceStart = Math.min(start, end);
        int replaceEnd = Math.max(start, end);
        editable.replace(replaceStart, replaceEnd, text);
        setSelection(replaceStart + text.length());
    }

    void deleteBeforeCursor() {
        Editable editable = getText();
        if (editable == null || editable.length() == 0) {
            return;
        }

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
            if (builtInKeyboard != null && builtInKeyboard.getVisibility() == VISIBLE) {
                hideBuiltInKeyboard();
                return true;
            }
            if (mEditorListener != null && mEditorListener.onEditorAction(this, android.R.id.closeButton, event)) {
                return true;
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }
}
