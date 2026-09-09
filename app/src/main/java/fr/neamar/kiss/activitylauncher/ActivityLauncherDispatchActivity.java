package fr.neamar.kiss.activitylauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URISyntaxException;

/**
 * Short-lived bridge used only when a saved target is a Service or BroadcastReceiver.
 * It has no discovery code, no workers and no background lifecycle.
 */
public final class ActivityLauncherDispatchActivity extends Activity {
    private static final String EXTRA_TARGET_URI =
            "com.tbzmike.smartslauncher.activitylauncher.TARGET_URI";
    private static final String EXTRA_KIND =
            "com.tbzmike.smartslauncher.activitylauncher.KIND";

    @NonNull
    static Intent createDispatchIntent(@NonNull Context context, @NonNull Intent target,
                                       @NonNull String kind) {
        return new Intent(context, ActivityLauncherDispatchActivity.class)
                .putExtra(EXTRA_TARGET_URI, target.toUri(0))
                .putExtra(EXTRA_KIND, kind);
    }

    @NonNull
    static ActivityLauncherStore.DecodedTarget decodeDispatchIntent(@NonNull Intent wrapper)
            throws URISyntaxException {
        String targetUri = wrapper.getStringExtra(EXTRA_TARGET_URI);
        String kind = wrapper.getStringExtra(EXTRA_KIND);
        if (targetUri == null || kind == null) throw new URISyntaxException("", "Missing dispatch target");
        return new ActivityLauncherStore.DecodedTarget(Intent.parseUri(targetUri, 0), kind);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean success = false;
        try {
            ActivityLauncherStore.DecodedTarget decoded = decodeDispatchIntent(getIntent());
            success = executeTarget(this, decoded.targetIntent, decoded.kind);
        } catch (Exception ignored) { }
        if (!success) Toast.makeText(this, "Unable to invoke saved target", Toast.LENGTH_LONG).show();
        finish();
    }

    public static boolean executeTarget(@NonNull Context context, @NonNull Intent target,
                                        @NonNull String kind) {
        Intent intent = new Intent(target);
        try {
            if (ActivityLauncherStore.KIND_SERVICE.equals(kind)) {
                return context.startService(intent) != null;
            }
            if (ActivityLauncherStore.KIND_BROADCAST.equals(kind)) {
                context.sendBroadcast(intent);
                return true;
            }
            if (!ActivityLauncherStore.KIND_ACTIVITY.equals(kind)) return false;
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | IllegalStateException | SecurityException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
