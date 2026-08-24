package fr.neamar.kiss.appusage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

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

        // Updates normally emit REMOVED(replacing), ADDED(replacing), then REPLACED. Ignore the
        // two intermediate broadcasts so one update produces one detailed history event.
        if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && replacing) return;
        if (Intent.ACTION_PACKAGE_ADDED.equals(action) && replacing) return;

        Context appContext = context.getApplicationContext();
        if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            if (!recordCurrentPackage(appContext, packageName, false)) {
                AppUsageSync.recordPackageChange(appContext, action, packageName, false);
            }
            return;
        }
        if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            if (!recordCurrentPackage(appContext, packageName, true)) {
                AppUsageSync.recordPackageChange(appContext, action, packageName, true);
            }
            return;
        }

        // For removals the package may already be invisible to PackageManager. The sync layer
        // intentionally reads the last locally remembered label/source before writing the event.
        AppUsageSync.recordPackageChange(appContext, action, packageName, replacing);
    }

    private static boolean recordCurrentPackage(Context context, String packageName,
                                                boolean update) {
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L));
            } else {
                //noinspection deprecation
                info = pm.getPackageInfo(packageName, 0);
            }

            String label = packageName;
            boolean system = false;
            ApplicationInfo app = info.applicationInfo;
            if (app != null) {
                CharSequence cs = pm.getApplicationLabel(app);
                if (cs != null && cs.length() > 0) label = cs.toString();
                system = (app.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            }

            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            String version = TextUtils.isEmpty(info.versionName)
                    ? "code " + code : "v" + info.versionName + ":" + code;
            String detail = version + (update ? " updated" : " installed");

            InstallMeta install = installMeta(pm, packageName);
            AppUsageStore store = AppUsageStore.get(context);
            long eventTime = update && info.lastUpdateTime > 0L
                    ? info.lastUpdateTime
                    : info.firstInstallTime > 0L ? info.firstInstallTime : System.currentTimeMillis();
            String kind = update ? AppUsageStore.KIND_UPDATED : AppUsageStore.KIND_INSTALLED;
            String eventKey = (update ? "update-live-detail:" : "install-live-detail:")
                    + packageName + ":" + eventTime;
            store.putTimeline(new AppUsageStore.TimelineEntry(
                    eventKey,
                    eventTime,
                    0L,
                    kind,
                    packageName,
                    label,
                    0L,
                    system,
                    detail,
                    install.source,
                    install.sourceUri));
            store.putPackageState(new AppUsageStore.PackageState(
                    packageName,
                    label,
                    system,
                    info.firstInstallTime,
                    info.lastUpdateTime,
                    install.source,
                    install.sourceUri));
            store.prune(System.currentTimeMillis());
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return false;
        }
    }

    private static InstallMeta installMeta(PackageManager pm, String packageName) {
        String installer = null;
        String sourceType = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo info = pm.getInstallSourceInfo(packageName);
                installer = info.getInstallingPackageName();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    sourceType = packageSourceLabel(info.getPackageSource());
                }
            } else {
                //noinspection deprecation
                installer = pm.getInstallerPackageName(packageName);
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }

        String installerLabel = installer;
        if (!TextUtils.isEmpty(installer)) {
            try {
                CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(installer, 0));
                if (cs != null && cs.length() > 0) installerLabel = cs.toString();
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
        }

        String source = null;
        if (!TextUtils.isEmpty(installerLabel) && !TextUtils.isEmpty(sourceType)) {
            source = installerLabel + " · " + sourceType + " · " + installer;
        } else if (!TextUtils.isEmpty(installerLabel)) {
            source = installerLabel + (TextUtils.equals(installerLabel, installer)
                    ? "" : " · " + installer);
        } else if (!TextUtils.isEmpty(sourceType)) {
            source = sourceType;
        }
        String sourceUri = "com.android.vending".equals(installer)
                ? "https://play.google.com/store/apps/details?id=" + packageName : null;
        return new InstallMeta(source, sourceUri);
    }

    private static String packageSourceLabel(int source) {
        switch (source) {
            case PackageInstaller.PACKAGE_SOURCE_STORE:
                return "Store";
            case PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE:
                return "Local file";
            case PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE:
                return "Downloaded file";
            case PackageInstaller.PACKAGE_SOURCE_OTHER:
                return "Other source";
            case PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED:
            default:
                return "Unspecified source";
        }
    }

    private static final class InstallMeta {
        final String source;
        final String sourceUri;
        InstallMeta(String source, String sourceUri) {
            this.source = source;
            this.sourceUri = sourceUri;
        }
    }
}
