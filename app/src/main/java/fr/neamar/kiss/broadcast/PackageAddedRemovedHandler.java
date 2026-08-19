package fr.neamar.kiss.broadcast;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.UserHandle;

public class PackageAddedRemovedHandler extends BroadcastReceiver {

    public static void handleEvent(@NonNull Context ctx, @Nullable String action, @NonNull String[] packageNames, @NonNull UserHandle user, boolean replacing) {
        if (packageNames.length == 1 && packageNames[0].equalsIgnoreCase(ctx.getPackageName())) return;

        if (Intent.ACTION_PACKAGE_ADDED.equals(action) && !replacing) {
            for (String packageName : packageNames) {
                if (PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("enable-app-history", true)) {
                    KissApplication.getApplication(ctx).getDataHandler().addPackageToHistory(ctx, user, packageName);
                }
            }
        }

        KissApplication.getMimeTypeCache(ctx).clearCache();

        if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            if (!replacing) {
                KissApplication.getApplication(ctx).resetIconsHandler();
                KissApplication.getApplication(ctx).getDataHandler().reloadApps();
                for (String packageName : packageNames) {
                    KissApplication.getApplication(ctx).getDataHandler().removeShortcuts(packageName);
                    KissApplication.getApplication(ctx).getDataHandler().removeFromExcluded(packageName);
                    // A genuine uninstall is the only time a remembered frozen app should vanish.
                    SmartStateStore.forgetPackage(ctx, packageName);
                }
            }
        } else {
            KissApplication.getApplication(ctx).resetIconsHandler();
            // Package changed/available events include freeze/unfreeze transitions. Always reload:
            // relying on getLaunchingComponent() would hide exactly the packages IceBox disabled.
            KissApplication.getApplication(ctx).getDataHandler().reloadApps();
            KissApplication.getApplication(ctx).getDataHandler().reloadShortcuts();
        }
    }

    private static boolean isAnyPackageVisible(Context ctx, String[] packageNames, UserHandle userHandle) {
        Set<String> excludedApps = KissApplication.getApplication(ctx).getDataHandler().getExcluded();
        for (String packageName : packageNames) {
            ComponentName launchingComponent = PackageManagerUtils.getLaunchingComponent(ctx, packageName, userHandle);
            if (launchingComponent != null) {
                boolean isExcluded = excludedApps.contains(AppPojo.getComponentName(launchingComponent.getPackageName(), launchingComponent.getClassName(), userHandle));
                if (!isExcluded) return true;
            }
        }
        return false;
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String[] packageNames = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
        if (packageNames == null && intent.getData() != null) {
            String packageName = intent.getData().getSchemeSpecificPart();
            packageNames = new String[]{packageName};
        }
        if (packageNames == null) return;
        handleEvent(ctx, intent.getAction(), packageNames, UserHandle.OWNER,
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false));
    }
}
