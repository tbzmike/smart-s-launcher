package fr.neamar.kiss.utils;

import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.shortcut.SaveAllOreoShortcutsAsync;
import fr.neamar.kiss.shortcut.SaveSingleOreoShortcutAsync;

public class ShortcutUtil {

    private final static String TAG = ShortcutUtil.class.getSimpleName();
    private static final String ICEBOX_PACKAGE = "com.catchingnow.icebox";

    public static String generateShortcutId(UserHandle userHandle, @NonNull ShortcutRecord shortcutRecord) {
        if (userHandle == null) {
            return ShortcutPojo.SCHEME + shortcutRecord.name.toLowerCase(Locale.ROOT);
        } else {
            return userHandle.addUserSuffixToString(ShortcutPojo.SCHEME + shortcutRecord.packageName + "/" + shortcutRecord.intentUri, '/');
        }
    }

    public static boolean areShortcutsEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return canDeviceShowShortcuts() && prefs.getBoolean("enable-shortcuts", true);
    }

    public static boolean canDeviceShowShortcuts() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public static void addAllShortcuts(Context context) {
        new SaveAllOreoShortcutsAsync(context).execute();
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public static void addShortcut(Context context, Intent intent) {
        new SaveSingleOreoShortcutAsync(context, intent).execute();
    }

    public static void removeAllShortcuts(Context context) {
        DBHelper.removeAllShortcuts(context);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public static List<ShortcutInfo> getAllShortcuts(Context context) {
        return getShortcuts(context, null);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public static List<ShortcutInfo> getShortcuts(Context context, String packageName) {
        List<ShortcutInfo> shortcutInfoList = new ArrayList<>();

        UserManager manager = ContextCompat.getSystemService(context, UserManager.class);
        LauncherApps launcherApps = ContextCompat.getSystemService(context, LauncherApps.class);

        if (launcherApps.hasShortcutHostPermission()) {
            LauncherApps.ShortcutQuery shortcutQuery = new LauncherApps.ShortcutQuery();
            shortcutQuery.setQueryFlags(FLAG_MATCH_DYNAMIC | FLAG_MATCH_MANIFEST | FLAG_MATCH_PINNED);

            if (!TextUtils.isEmpty(packageName)) {
                shortcutQuery.setPackage(packageName);
            }

            for (android.os.UserHandle profile : manager.getUserProfiles()) {
                if (manager.isUserRunning(profile) && manager.isUserUnlocked(profile)) {
                    List<ShortcutInfo> shortcuts = launcherApps.getShortcuts(shortcutQuery, profile);
                    if (shortcuts != null) shortcutInfoList.addAll(shortcuts);
                }
            }
        }
        return shortcutInfoList;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public static ShortcutInfo getShortCut(Context context, @NonNull android.os.UserHandle user,
                                           String packageName, String shortcutId) {
        final LauncherApps launcherApps = ContextCompat.getSystemService(context, LauncherApps.class);

        if (launcherApps.hasShortcutHostPermission() && !TextUtils.isEmpty(packageName)) {
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
            query.setPackage(packageName);
            query.setShortcutIds(Collections.singletonList(shortcutId));
            query.setQueryFlags(FLAG_MATCH_DYNAMIC | FLAG_MATCH_MANIFEST | FLAG_MATCH_PINNED);

            final UserManager userManager = ContextCompat.getSystemService(context, UserManager.class);
            if (userManager.isUserRunning(user) && userManager.isUserUnlocked(user)) {
                List<ShortcutInfo> shortcuts = launcherApps.getShortcuts(query, user);
                if (shortcuts != null) {
                    for (ShortcutInfo shortcut : shortcuts) {
                        if (shortcut.isEnabled()) return shortcut;
                    }
                }
            }
        }
        return null;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Nullable
    public static ShortcutRecord createShortcutRecord(Context context, ShortcutInfo shortcutInfo,
                                                      boolean includePackageName) {
        if (shortcutInfo.hasKeyFieldsOnly()) {
            shortcutInfo = getShortCut(context, shortcutInfo.getUserHandle(), shortcutInfo.getPackage(), shortcutInfo.getId());
            if (shortcutInfo == null) return null;
        }

        ShortcutRecord record = new ShortcutRecord();
        record.packageName = shortcutInfo.getPackage();
        record.targetPackage = resolveShortcutTargetPackage(context, shortcutInfo);
        record.intentUri = ShortcutPojo.OREO_PREFIX + shortcutInfo.getId();

        String appName = PackageManagerUtils.getLabel(context, shortcutInfo.getPackage(),
                new UserHandle(context, shortcutInfo.getUserHandle()));

        if (shortcutInfo.getShortLabel() != null) {
            if (includePackageName && !TextUtils.isEmpty(appName)) {
                record.name = appName + ": " + shortcutInfo.getShortLabel().toString();
            } else {
                record.name = shortcutInfo.getShortLabel().toString();
            }
        } else if (shortcutInfo.getLongLabel() != null) {
            if (includePackageName && !TextUtils.isEmpty(appName)) {
                record.name = appName + ": " + shortcutInfo.getLongLabel().toString();
            } else {
                record.name = shortcutInfo.getLongLabel().toString();
            }
        } else {
            Log.d(TAG, "Invalid shortcut for " + record.packageName + ", ignoring");
            return null;
        }
        return record;
    }

    /**
     * Resolve the app actually launched by an Android shortcut. For ordinary shortcuts the target
     * is normally the publisher itself. For wrapper shortcuts (notably IceBox) inspect the full
     * launch intent chain and extras before the ShortcutInfo is reduced to ShortcutPojo.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    @Nullable
    public static String resolveShortcutTargetPackage(@NonNull Context context,
                                                      @NonNull ShortcutInfo shortcutInfo) {
        String publisher = shortcutInfo.getPackage();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        Intent[] intents = shortcutInfo.getIntents();
        if (intents != null) {
            // Android launches the final intent last; inspect from last to first so the concrete
            // target takes precedence over wrapper/bootstrap intents.
            for (int i = intents.length - 1; i >= 0; i--) {
                collectIntentPackages(intents[i], candidates);
            }
        }

        for (String candidate : candidates) {
            if (!TextUtils.equals(candidate, publisher) && isInstalledPackage(context, candidate)) {
                return candidate;
            }
        }

        // A normal app-owned shortcut legitimately targets its own publisher. IceBox is different:
        // its app shortcuts represent other frozen apps, so never treat IceBox as the notification
        // owner merely because it published the shortcut.
        if (isIceBoxPublisher(context, publisher)) return null;
        return isInstalledPackage(context, publisher) ? publisher : null;
    }

    private static void collectIntentPackages(@Nullable Intent intent, @NonNull Set<String> out) {
        if (intent == null) return;
        if (intent.getComponent() != null) out.add(intent.getComponent().getPackageName());
        if (!TextUtils.isEmpty(intent.getPackage())) out.add(intent.getPackage());
        collectUriPackages(intent.getData(), out);
        collectBundlePackages(intent.getExtras(), out);
    }

    private static void collectBundlePackages(@Nullable Bundle extras, @NonNull Set<String> out) {
        if (extras == null) return;
        for (String key : extras.keySet()) {
            Object value;
            try { value = extras.get(key); }
            catch (RuntimeException ignored) { continue; }
            if (value instanceof Intent) {
                collectIntentPackages((Intent) value, out);
            } else if (value instanceof Uri) {
                collectUriPackages((Uri) value, out);
            } else if (value instanceof String) {
                collectCandidateString((String) value, out);
            }
        }
    }

    private static void collectUriPackages(@Nullable Uri uri, @NonNull Set<String> out) {
        if (uri == null) return;
        try {
            for (String name : uri.getQueryParameterNames()) {
                for (String value : uri.getQueryParameters(name)) collectCandidateString(value, out);
            }
        } catch (UnsupportedOperationException ignored) { }
        for (String segment : uri.getPathSegments()) collectCandidateString(segment, out);
        collectCandidateString(uri.getHost(), out);
    }

    private static void collectCandidateString(@Nullable String raw, @NonNull Set<String> out) {
        if (TextUtils.isEmpty(raw)) return;
        String value = raw.trim();
        if (looksLikePackageName(value)) {
            out.add(value);
            return;
        }
        int slash = value.indexOf('/');
        if (slash > 0) {
            String prefix = value.substring(0, slash);
            if (looksLikePackageName(prefix)) out.add(prefix);
        }
        // Also inspect URI/intent-like strings that carry package values in their encoded form.
        String[] tokens = value.split("[^A-Za-z0-9._]+");
        for (String token : tokens) if (looksLikePackageName(token)) out.add(token);
    }

    private static boolean looksLikePackageName(@Nullable String value) {
        if (TextUtils.isEmpty(value) || value.length() < 3 || value.indexOf('.') <= 0) return false;
        if (value.startsWith("http.") || value.startsWith("https.")) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '_')) return false;
        }
        return true;
    }

    private static boolean isInstalledPackage(@NonNull Context context, @Nullable String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        try {
            context.getPackageManager().getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /** Detect IceBox without relying solely on one package id; package id remains the primary key. */
    public static boolean isIceBoxPublisher(@NonNull Context context, @Nullable String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        if (ICEBOX_PACKAGE.equals(packageName)) return true;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            CharSequence label = pm.getApplicationLabel(info);
            if (label == null) return false;
            String normalized = label.toString().replace(" ", "").trim();
            return "icebox".equalsIgnoreCase(normalized);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Nullable
    public static String getComponentName(@NonNull Context context, @Nullable ShortcutInfo shortcutInfo) {
        if (shortcutInfo != null && shortcutInfo.getActivity() != null) {
            UserHandle user = new UserHandle(context, shortcutInfo.getUserHandle());
            return AppPojo.getComponentName(shortcutInfo.getPackage(), shortcutInfo.getActivity().getClassName(), user);
        }
        return null;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public static boolean isShortcutVisible(@NonNull Context context, @NonNull ShortcutInfo shortcutInfo,
                                            @NonNull Set<String> excludedApps,
                                            @NonNull Set<String> excludedShortcutApps) {
        if (!shortcutInfo.isEnabled()) return false;

        UserManager userManager = ContextCompat.getSystemService(context, UserManager.class);
        LauncherApps launcherApps = ContextCompat.getSystemService(context, LauncherApps.class);
        if (PackageManagerUtils.isPrivateProfile(launcherApps, shortcutInfo.getUserHandle())) {
            if (userManager.isQuietModeEnabled(shortcutInfo.getUserHandle())) return false;
        }

        String packageName = shortcutInfo.getPackage();
        String componentName = ShortcutUtil.getComponentName(context, shortcutInfo);
        boolean isExcluded = excludedApps.contains(componentName) || excludedShortcutApps.contains(packageName);
        return !isExcluded;
    }
}
