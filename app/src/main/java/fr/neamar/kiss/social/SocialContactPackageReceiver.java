package fr.neamar.kiss.social;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Keeps the per-app communications index state accurate as social apps are installed/updated. */
public class SocialContactPackageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            SocialContactIndexService.maybePrompt(context);
            return;
        }

        Uri data = intent.getData();
        String packageName = data == null ? null : data.getSchemeSpecificPart();
        if (packageName == null || packageName.isEmpty()) return;

        if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            SocialContactIndexService.forgetPackage(context, packageName);
        } else if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            SocialContactIndexService.markPackageStale(context, packageName);
        }
        SocialContactIndexService.maybePrompt(context);
    }
}
