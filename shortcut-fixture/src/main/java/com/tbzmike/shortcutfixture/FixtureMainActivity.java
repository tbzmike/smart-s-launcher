package com.tbzmike.shortcutfixture;

import android.app.Activity;
import android.os.Bundle;

/** A main-screen launch deliberately reports a different destination from a shortcut. */
public final class FixtureMainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FixtureTargetActivity.report(this, "main", getIntent().getDataString());
        finish();
    }
}
