package com.tbzmike.shortcutfixture;

import android.app.Activity;
import android.os.Bundle;

/** Normal shortcut destinations can be private to their publisher, as many chat routes are. */
public final class FixturePrivateTargetActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FixtureTargetActivity.report(this, getIntent().getStringExtra("destination"),
                getIntent().getDataString());
        finish();
    }
}
