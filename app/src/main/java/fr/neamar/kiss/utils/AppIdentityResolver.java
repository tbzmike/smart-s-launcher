package fr.neamar.kiss.utils;

import android.content.ComponentName;
import android.content.Context;
import android.os.UserManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.db.AppCatalogRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;

/**
 * Resolves wrapper shortcuts (notably IceBox app shortcuts) to the real application identity.
 *
 * The shortcut remains a valid launch route, but usage/launch statistics and Pixel predictions can
 * treat it as the same logical app as the normal app:// entry. Ordinary in-app shortcuts are not
 * collapsed: only shortcuts whose verified target package differs from their publisher, or an
 * IceBox shortcut whose target can be recovered by label, are aliases.
 */
public final class AppIdentityResolver {
    private static final String PACKAGE_KEY_PREFIX = "package://";

    private AppIdentityResolver() { }

    @Nullable
    public static String canonicalPackage(@NonNull Context context,
                                          @NonNull DataHandler dataHandler,
                                          @Nullable Pojo pojo) {
        if (pojo instanceof AppPojo) {
            return ((AppPojo) pojo).packageName;
        }
        if (pojo instanceof DisabledAppPojo) {
            return emptyToNull(((DisabledAppPojo) pojo).targetPackage);
        }
        if (!(pojo instanceof ShortcutPojo)) return null;

        ShortcutPojo shortcut = (ShortcutPojo) pojo;
        String target = emptyToNull(shortcut.targetPackage);
        if (target != null && !TextUtils.equals(target, shortcut.packageName)) {
            return target;
        }

        // Older Smart S builds remembered the IceBox shortcut itself but did not persist the
        // resolved target package. Recover that identity from the already-loaded app catalog.
        if (!ShortcutUtil.isIceBoxPublisher(context, shortcut.packageName)) return null;
        String wantedLabel = cleanIceBoxLabel(shortcut.getName());
        if (TextUtils.isEmpty(wantedLabel)) return null;

        List<AppPojo> apps = dataHandler.getApplications();
        if (apps == null) return null;
        for (AppPojo app : apps) {
            if (app == null || TextUtils.isEmpty(app.packageName)) continue;
            String appLabel = app.getName();
            if (!TextUtils.isEmpty(appLabel)
                    && wantedLabel.equalsIgnoreCase(appLabel.trim())) {
                return app.packageName;
            }
        }
        return null;
    }

    public static boolean isAppAliasShortcut(@NonNull Context context,
                                             @NonNull DataHandler dataHandler,
                                             @Nullable Pojo pojo) {
        if (!(pojo instanceof ShortcutPojo)) return false;
        ShortcutPojo shortcut = (ShortcutPojo) pojo;
        String canonical = canonicalPackage(context, dataHandler, shortcut);
        return !TextUtils.isEmpty(canonical)
                && !TextUtils.equals(canonical, shortcut.packageName);
    }

    /**
     * Identity key used only for grouping/deduplication. Non-app shortcuts keep their exact
     * history identity and therefore remain independent launcher features.
     */
    @NonNull
    public static String canonicalSelectionKey(@NonNull Context context,
                                               @NonNull DataHandler dataHandler,
                                               @NonNull Pojo pojo) {
        String packageName = canonicalPackage(context, dataHandler, pojo);
        if (!TextUtils.isEmpty(packageName)
                && (pojo instanceof AppPojo
                || pojo instanceof DisabledAppPojo
                || isAppAliasShortcut(context, dataHandler, pojo))) {
            return PACKAGE_KEY_PREFIX + packageName;
        }
        String historyId = pojo.getHistoryId();
        return TextUtils.isEmpty(historyId) ? pojo.id : historyId;
    }

    /**
     * Convert an alias launch to the actual app history identity whenever the app provider knows
     * that package. This makes future IceBox launches increment the same history record as tapping
     * the real app icon, without changing the shortcut's launch mechanism.
     */
    @Nullable
    public static String canonicalHistoryId(@NonNull Context context,
                                            @NonNull DataHandler dataHandler,
                                            @Nullable Pojo pojo) {
        if (!(pojo instanceof ShortcutPojo)
                || !isAppAliasShortcut(context, dataHandler, pojo)) {
            return pojo == null ? null : pojo.getHistoryId();
        }

        String packageName = canonicalPackage(context, dataHandler, pojo);
        if (TextUtils.isEmpty(packageName)) return pojo.getHistoryId();

        AppPojo app = findApp(dataHandler, packageName);
        if (app != null) return app.getHistoryId();

        ShortcutPojo shortcut = (ShortcutPojo) pojo;
        UserHandle user = shortcut.getUserHandle();

        // A freezer can remove the target from LauncherApps immediately after the shortcut is
        // clicked. Resolve the canonical app identity from Smart S's persistent app catalog before
        // falling back to the wrapper id, so IceBox launches never become IceBox statistics merely
        // because the target is frozen at history-write time.
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (userManager != null) {
            long serial = userManager.getSerialNumberForUser(user.getRealHandle());
            if (serial >= 0L) {
                for (AppCatalogRecord record : SmartStateStore.getRememberedApps(context, serial)) {
                    if (record == null
                            || !TextUtils.equals(packageName, record.packageName)
                            || TextUtils.isEmpty(record.activityName)) {
                        continue;
                    }
                    return user.addUserSuffixToString(
                            "app://" + packageName + "/" + record.activityName, '/');
                }
            }
        }

        ComponentName launcher = PackageManagerUtils.getLaunchingComponent(
                context, packageName, user);
        if (launcher != null) {
            return user.addUserSuffixToString(
                    "app://" + launcher.getPackageName() + "/" + launcher.getClassName(), '/');
        }
        return pojo.getHistoryId();
    }

    @Nullable
    public static AppPojo findApp(@NonNull DataHandler dataHandler,
                                  @Nullable String packageName) {
        if (TextUtils.isEmpty(packageName)) return null;
        List<AppPojo> apps = dataHandler.getApplications();
        if (apps == null) return null;

        AppPojo disabledFallback = null;
        for (AppPojo app : apps) {
            if (app == null || !packageName.equals(app.packageName)) continue;
            if (!app.isDisabled()) return app;
            if (disabledFallback == null) disabledFallback = app;
        }
        return disabledFallback;
    }

    @NonNull
    public static String cleanIceBoxLabel(@Nullable String label) {
        if (label == null) return "";
        String value = label.trim();
        if (value.regionMatches(true, 0, "Ice Box:", 0, "Ice Box:".length())) {
            value = value.substring("Ice Box:".length()).trim();
        } else if (value.regionMatches(true, 0, "IceBox:", 0, "IceBox:".length())) {
            value = value.substring("IceBox:".length()).trim();
        }
        // IceBox commonly prefixes its frozen-app shortcuts with a snowflake/variation selector.
        while (value.startsWith("❄") || value.startsWith("️")) {
            value = value.substring(1).trim();
        }
        return value;
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }
}
