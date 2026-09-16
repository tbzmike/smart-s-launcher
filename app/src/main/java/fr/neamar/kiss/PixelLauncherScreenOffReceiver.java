package fr.neamar.kiss;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import fr.neamar.kiss.utils.Log;

/**
 * Releases Pixel Launcher's background process when the device becomes non-interactive.
 *
 * This receiver is registered from KissApplication because ACTION_SCREEN_OFF cannot be received
 * by a manifest-declared receiver. The actual force-stop is performed off the main thread and uses
 * Smart S's existing root shell implementation, so there is no persistent worker/service for this
 * optional feature.
 */
public final class PixelLauncherScreenOffReceiver extends BroadcastReceiver {
    private static final String TAG = PixelLauncherScreenOffReceiver.class.getSimpleName();
    public static final String PREFERENCE_KEY = "kill-pixel-launcher-on-screen-off";
    private static final String PIXEL_LAUNCHER_PACKAGE = "com.google.android.apps.nexuslauncher";

    public static void register(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        context.getApplicationContext().registerReceiver(new PixelLauncherScreenOffReceiver(), filter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (!preferences.getBoolean(PREFERENCE_KEY, false)) return;

        final PendingResult pendingResult = goAsync();
        Thread worker = new Thread(() -> {
            try {
                boolean stopped = KissApplication.getApplication(context)
                        .getRootHandler()
                        .hibernateApp(PIXEL_LAUNCHER_PACKAGE);
                if (!stopped) {
                    Log.w(TAG, "Unable to force-stop Pixel Launcher; root access may be unavailable");
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Pixel Launcher screen-off cleanup failed", e);
            } finally {
                pendingResult.finish();
            }
        }, "smart-s-pixel-launcher-stop");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }
}
