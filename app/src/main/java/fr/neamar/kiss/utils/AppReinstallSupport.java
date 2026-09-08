package fr.neamar.kiss.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import fr.neamar.kiss.appusage.AppUsageStore;

/** Reopens the exact recorded installer only when Android can verify the stored store route. */
public final class AppReinstallSupport {
    private AppReinstallSupport() {}

    public static boolean hasVerifiedReinstallRoute(@NonNull Context context,
                                                    @NonNull String packageName) {
        return buildVerifiedStoreIntent(context, packageName) != null;
    }

    public static boolean openOriginalStore(@NonNull Context context,
                                            @NonNull String packageName) {
        Intent intent = buildVerifiedStoreIntent(context, packageName);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            Log.w("AppReinstallSupport", "Unable to open recorded installer route", e);
            return false;
        }
    }

    public static void showUninstalledDialog(@NonNull Context context,
                                             @NonNull String packageName,
                                             @Nullable String appName) {
        AppUsageStore.PackageState state = AppUsageStore.get(context).getPackageState(packageName);
        String label = TextUtils.isEmpty(appName) ? packageName : appName;
        String source = state == null || TextUtils.isEmpty(state.source)
                ? "The original install source was not retained."
                : "Original install source: " + state.source;
        boolean canReinstall = hasVerifiedReinstallRoute(context, packageName);
        AlertDialog.Builder dialog = new AlertDialog.Builder(context)
                .setTitle(label + " is uninstalled")
                .setMessage(source + (canReinstall
                        ? "\n\nSmart S can reopen the verified original store page."
                        : "\n\nNo verified reinstall route is available for that source."))
                .setNegativeButton(android.R.string.cancel, null);
        if (canReinstall) {
            dialog.setPositiveButton("Reinstall", (d, which) -> {
                if (!openOriginalStore(context, packageName)) {
                    Toast.makeText(context, "The original store could not be opened.",
                            Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            dialog.setPositiveButton(android.R.string.ok, null);
        }
        dialog.show();
    }

    @Nullable
    private static Intent buildVerifiedStoreIntent(@NonNull Context context,
                                                   @NonNull String packageName) {
        AppUsageStore.PackageState state = AppUsageStore.get(context).getPackageState(packageName);
        if (state == null) return null;
        String installer = state.installerPackage;
        if (TextUtils.isEmpty(installer) && !TextUtils.isEmpty(state.sourceUri)
                && state.sourceUri.contains("play.google.com/store/apps/details")) {
            // Older Smart S package-state rows predate the structured installer column; this
            // canonical Play URL is sufficient evidence that the recorded installer was Play.
            installer = "com.android.vending";
        }
        if (TextUtils.isEmpty(installer)) return null;

        String[] candidates = TextUtils.isEmpty(state.sourceUri)
                ? new String[]{"market://details?id=" + Uri.encode(packageName)}
                : new String[]{state.sourceUri, "market://details?id=" + Uri.encode(packageName)};
        PackageManager pm = context.getPackageManager();
        for (String uri : candidates) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(installer);
                ResolveInfo resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (resolved != null && resolved.activityInfo != null
                        && TextUtils.equals(installer, resolved.activityInfo.packageName)) {
                    return intent;
                }
            } catch (RuntimeException ignored) { }
        }
        return null;
    }
}
