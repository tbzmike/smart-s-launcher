package fr.neamar.kiss.appusage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public final class AppUsagePackageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AppUsageTracker.isEnabled(context)) return;
        Uri data = intent.getData();
        if (data == null) return;
        String packageName = data.getSchemeSpecificPart();
        if (packageName == null || packageName.isEmpty()) return;
        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        String action = intent.getAction();
        if (action == null) return;

        // An update normally emits REMOVED(replacing), ADDED(replacing), then REPLACED. Ignore
        // the intermediate ADDED broadcast so one update produces one timeline event.
        if (Intent.ACTION_PACKAGE_ADDED.equals(action) && replacing) return;

        AppUsageSync.recordPackageChange(
                context.getApplicationContext(), action, packageName, replacing);
    }
}
