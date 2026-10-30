package com.tbzmike.shortcutfixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Records the actual destination/extra reached; a successful API return alone cannot pass. */
public final class FixtureTargetActivity extends Activity {
    public static final String HIT = "com.tbzmike.shortcutfixture.HIT";
    public static final String DESTINATION = "destination";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getPackageName().endsWith(".icebox")) {
            Intent exact = new Intent(getIntent()).setComponent(new ComponentName(
                    "com.tbzmike.shortcutfixture.target", FixtureTargetActivity.class.getName()));
            startActivity(exact);
        } else {
            report(this, getIntent().getStringExtra(DESTINATION), getIntent().getDataString());
        }
        finish();
    }
    static void report(Context context, String destination, String data) {
        context.sendBroadcast(new Intent(HIT).setPackage("com.tbzmike.smartslauncher.debug")
                .putExtra("publisher", context.getPackageName())
                .putExtra(DESTINATION, destination).putExtra("data", data));
    }
}
