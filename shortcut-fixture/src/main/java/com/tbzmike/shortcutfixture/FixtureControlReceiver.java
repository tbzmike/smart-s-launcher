package com.tbzmike.shortcutfixture;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.net.Uri;
import java.util.Arrays;
import java.util.Collections;

/** Installed only in the CI emulator; never part of the production launcher APK. */
public final class FixtureControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent control) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        String mode = control.getStringExtra("mode");
        if ("removeA".equals(mode)) {
            manager.removeDynamicShortcuts(Collections.singletonList("fixture-A"));
        } else if ("clear".equals(mode)) {
            manager.removeAllDynamicShortcuts();
        } else {
            manager.addDynamicShortcuts(Arrays.asList(shortcut(context, "fixture-A"),
                    shortcut(context, "fixture-B")));
        }
    }
    private static ShortcutInfo shortcut(Context context, String id) {
        Intent exact = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(context, context.getPackageName().endsWith(".icebox")
                        ? FixtureTargetActivity.class : FixturePrivateTargetActivity.class))
                .setData(Uri.parse("fixture://exact/" + id))
                .putExtra(FixtureTargetActivity.DESTINATION, id);
        return new ShortcutInfo.Builder(context, id).setShortLabel(id)
                .setActivity(new ComponentName(context, FixtureMainActivity.class))
                .setIntent(exact).build();
    }
}
