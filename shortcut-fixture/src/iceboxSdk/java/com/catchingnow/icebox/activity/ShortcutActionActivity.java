package com.catchingnow.icebox.activity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Mirrors Ice Box's exported run_app URI boundary; the test supplies its freezer authority. */
public final class ShortcutActionActivity extends Activity {
    @Override public void onCreate(Bundle state) { super.onCreate(state); open(getIntent()); }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); open(intent); }
    private void open(Intent intent) {
        Uri uri = intent.getData();
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) || uri == null
                || !"icebox".equals(uri.getScheme()) || !"action".equals(uri.getHost())
                || uri.getPathSegments().size() != 2
                || !"run_app".equals(uri.getPathSegments().get(0))
                || !"0".equals(uri.getQueryParameter("user"))) {
            finish(); return;
        }
        String target = uri.getLastPathSegment();
        Intent request = new Intent("com.tbzmike.shortcutfixture.ICEBOX_NATIVE")
                .setPackage("com.tbzmike.smartslauncher.debug")
                .putExtra("target", target).putExtra("uri", uri.toString());
        sendOrderedBroadcast(request, null, new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent completed) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(target);
                if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
            }
        }, null, 0, null, null);
    }
}
