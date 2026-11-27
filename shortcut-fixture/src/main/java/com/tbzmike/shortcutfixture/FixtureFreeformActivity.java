package com.tbzmike.shortcutfixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Persistent, resizable test window. Existing shortcut fixtures intentionally finish immediately. */
public final class FixtureFreeformActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("Native freeform fixture: " + getPackageName());
        label.setTextSize(20);
        setContentView(label);
    }
}
