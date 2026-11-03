package fr.neamar.kiss.utils;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

/** On-demand Ice Box SDK route for an app shortcut whose Android shortcut was withdrawn. */
public final class IceBoxSdkBridge {
    public static final String PERMISSION = "com.catchingnow.icebox.SDK";
    private static final String PUBLISHER = "com.catchingnow.icebox";
    private static final String AUTHORITY = "com.catchingnow.icebox.SDK";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORK = IdleExecutors.single("smart-s-icebox-launch");

    private IceBoxSdkBridge() { }

    public static boolean available(Context context, String publisher) {
        if (!PUBLISHER.equals(publisher)) return false;
        try {
            ProviderInfo provider = context.getPackageManager().resolveContentProvider(AUTHORITY, 0);
            return provider != null && PUBLISHER.equals(provider.packageName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** The SDK call is synchronous; keep it off the UI thread and confirm the actual app state. */
    public static void unfreezeAsync(Context context, String target, Consumer<Boolean> completed) {
        Context app = context.getApplicationContext();
        try {
            WORK.execute(() -> {
                boolean enabled = false;
                try {
                    int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? PendingIntent.FLAG_IMMUTABLE : 0;
                    PendingIntent authorization = PendingIntent.getBroadcast(app, 0x333,
                            new Intent(app, AuthorizationReceiver.class), flags);
                    Bundle extras = new Bundle();
                    extras.putParcelable("authorize", authorization);
                    extras.putStringArray("package_names", new String[]{target});
                    extras.putInt("user_handle", Process.myUserHandle().hashCode());
                    extras.putBoolean("enable", true);
                    app.getContentResolver().call(Uri.parse("content://" + AUTHORITY),
                            "set_enable", null, extras);
                    long deadline = SystemClock.elapsedRealtime() + 5_000L;
                    do {
                        if (AppLaunchUtils.isPackageEnabledNow(app, target)) { enabled = true; break; }
                        SystemClock.sleep(100L);
                    } while (SystemClock.elapsedRealtime() < deadline);
                } catch (RuntimeException e) {
                    Log.w("IceBoxSdkBridge", "Ice Box could not enable " + target, e);
                }
                boolean result = enabled;
                MAIN.post(() -> completed.accept(result));
            });
        } catch (RuntimeException e) {
            MAIN.post(() -> completed.accept(false));
        }
    }

    /** Gives Ice Box a PendingIntent with which to identify the calling app, as its SDK requires. */
    public static final class AuthorizationReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) { }
    }
}
