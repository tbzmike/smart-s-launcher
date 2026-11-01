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
        if (manager == null) { setResultCode(-1); return; }
        String mode = control.getStringExtra("mode");
        if (mode != null && mode.startsWith("notify-")) {
            notifications(context, mode);
        } else if ("removeA".equals(mode)) {
            manager.removeDynamicShortcuts(Collections.singletonList("fixture-A"));
        } else if ("clear".equals(mode)) {
            manager.removeAllDynamicShortcuts();
        } else {
            if (!manager.addDynamicShortcuts(Arrays.asList(shortcut(context, "fixture-A"),
                    shortcut(context, "fixture-B")))) {
                setResultCode(-1);
                return;
            }
        }
        setResultCode(1); // Ordered broadcast acknowledgement: the fixture really ran.
    }

    private static void notifications(Context context, String mode) {
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        if ("notify-clear".equals(mode)) { manager.cancelAll(); return; }
        manager.createNotificationChannel(new android.app.NotificationChannel(
                "power187", "Power test", android.app.NotificationManager.IMPORTANCE_LOW));
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(
                256, 256, android.graphics.Bitmap.Config.ARGB_8888);
        image.eraseColor(android.graphics.Color.BLUE);
        int count = "notify-burst".equals(mode) ? 80 : "notify-load".equals(mode) ? 32 : 1;
        for (int i = 0; i < count; i++) {
            boolean ongoing = "notify-burst".equals(mode);
            android.app.Notification n = new android.app.Notification.Builder(context, "power187")
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setLargeIcon(image)
                    .setContentTitle("Power fixture " + i).setContentText("Exact live preview " + i)
                    .setStyle(new android.app.Notification.BigPictureStyle().bigPicture(image))
                    .setOngoing(ongoing).setOnlyAlertOnce(true).build();
            manager.notify(ongoing ? 8799 : 8700 + i, n);
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
