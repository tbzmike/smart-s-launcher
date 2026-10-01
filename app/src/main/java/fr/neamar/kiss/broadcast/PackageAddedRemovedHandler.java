package fr.neamar.kiss.broadcast;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.List;
import java.util.Set;

import fr.neamar.kiss.DataHandler;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.dataprovider.AppProvider;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.FrozenAppPreferences;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.UserHandle;

public class PackageAddedRemovedHandler extends BroadcastReceiver {

    public static void handleEvent(@NonNull Context ctx, @Nullable String action, @NonNull String[] packageNames, @NonNull UserHandle user, boolean replacing) {
        if (packageNames.length == 1 && packageNames[0].equalsIgnoreCase(ctx.getPackageName())) return;
        if (!FrozenAppPreferences.monitorPackageChanges(ctx)) return;

        // Android emits package callbacks for many packages that can never appear in this
        // launcher. A full app + shortcut provider reload for those events is pure churn and was
        // visible in the supplied trace as overlapping expensive provider scans. Ignore unrelated
        // packages before touching icon caches or providers.
        boolean launcherRelevant = false;
        for (String packageName : packageNames) {
            if (isLauncherRelevantPackage(ctx, packageName, user)) {
                launcherRelevant = true;
                break;
            }
        }
        if (!launcherRelevant) return;

        // Freeze/unfreeze changes must be reflected immediately. AppLaunchUtils normally caches
        // package enabled state for a short period, which is useful during ordinary rendering but
        // must never survive an explicit package state callback.
        for (String packageName : packageNames) {
            AppLaunchUtils.invalidatePackageState(packageName);
        }

        if (Intent.ACTION_PACKAGE_ADDED.equals(action) && !replacing) {
            for (String packageName : packageNames) {
                if (PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("enable-app-history", true)) {
                    KissApplication.getApplication(ctx).getDataHandler().addPackageToHistory(ctx, user, packageName);
                }
            }
        }

        KissApplication.getMimeTypeCache(ctx).clearCache();

        if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && !replacing) {
            boolean anyTrueUninstall = false;
            boolean anyStillInstalled = false;
            for (String packageName : packageNames) {
                if (isInstalledIncludingDisabled(ctx, packageName)) {
                    // Some package managers/freezers can produce removal-like visibility events for
                    // an app that remains installed. This is a frozen state, not an uninstall.
                    anyStillInstalled = true;
                    continue;
                }
                anyTrueUninstall = true;
                KissApplication.getApplication(ctx).getDataHandler().removeShortcuts(packageName);
                KissApplication.getApplication(ctx).getDataHandler().removeFromExcluded(packageName);
                SmartStateStore.forgetPackage(ctx, packageName);
            }

            KissApplication.getApplication(ctx).resetIconsHandler();
            KissApplication.getApplication(ctx).getDataHandler().reloadApps();
            if (anyStillInstalled) {
                KissApplication.getApplication(ctx).getDataHandler().reloadShortcuts();
            }
            if (!anyTrueUninstall && anyStillInstalled) {
                // The package remains installed; preserve history/catalog identity and simply let
                // the reloaded AppPojo expose its disabled icon state.
                return;
            }
        } else {
            KissApplication.getApplication(ctx).resetIconsHandler();
            // Package changed/available events include freeze/unfreeze transitions. When HOME is
            // already visible, keep the warm provider snapshot for the first frame and coalesce the
            // expensive canonical-app + shortcut reload into one delayed pass. The logcat showed
            // duplicate ShortcutsProvider restarts and a 7.6s AppProvider/ShortcutsProvider load
            // during HOME, which starved the main thread and produced the black screen.
            if (!AppProvider.deferPackageReloadWhileHomeVisible(true)) {
                KissApplication.getApplication(ctx).getDataHandler().reloadApps();
                KissApplication.getApplication(ctx).getDataHandler().reloadShortcuts();
            }
        }
    }

    private static boolean isLauncherRelevantPackage(
            Context ctx, String packageName, UserHandle userHandle) {
        if (packageName == null || packageName.isEmpty()) return false;

        DataHandler dataHandler = KissApplication.getApplication(ctx).getDataHandler();
        if (dataHandler != null) {
            List<AppPojo> apps = dataHandler.getApplications();
            if (apps != null) {
                for (AppPojo app : apps) {
                    if (app != null && packageName.equals(app.packageName)) return true;
                }
            }

            fr.neamar.kiss.dataprovider.ShortcutsProvider shortcuts =
                    dataHandler.getShortcutsProvider();
            if (shortcuts != null) {
                for (ShortcutPojo shortcut : shortcuts.getPojos()) {
                    if (shortcut != null && packageName.equals(shortcut.packageName)) return true;
                }
            }
        }

        // New launcher apps are not in our current provider snapshot yet.
        return PackageManagerUtils.getLaunchingComponent(ctx, packageName, userHandle) != null;
    }

    private static boolean isInstalledIncludingDisabled(Context ctx, String packageName) {
        try {
            ctx.getPackageManager().getApplicationInfo(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
            return false;
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
