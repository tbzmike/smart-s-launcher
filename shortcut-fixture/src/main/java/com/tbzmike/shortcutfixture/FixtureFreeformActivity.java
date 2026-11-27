package com.tbzmike.shortcutfixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Persistent, resizable test window. Existing shortcut fixtures intentionally finish immediately. */
public final class FixtureFreeformActivity extends Activity {
    @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            android.util.Log.i("FreeformFixture", "Back received in task " + getTaskId());
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setTitle("Freeform custom title " + getPackageName());
        TextView label = new TextView(this);
        label.setText("Native freeform fixture: " + getPackageName());
        label.setTextSize(20);
        label.setBackgroundColor(getPackageName().endsWith(".target") ? 0xffbd24d6 : 0xff11b8d5);
        label.setTextColor(android.graphics.Color.WHITE);
        setContentView(label);
    }
}
