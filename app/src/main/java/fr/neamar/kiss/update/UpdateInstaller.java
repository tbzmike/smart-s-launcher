package fr.neamar.kiss.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Verifies package identity, signer continuity and version monotonicity before installation. */
public final class UpdateInstaller {
    private UpdateInstaller() {
    }

    public static final class VerificationResult {
        public final boolean valid;
        public final String message;
        public final String versionName;
        public final long versionCode;

        VerificationResult(boolean valid, String message, String versionName, long versionCode) {
            this.valid = valid;
            this.message = message;
            this.versionName = versionName == null ? "" : versionName;
            this.versionCode = Math.max(0L, versionCode);
        }
    }

    public static File finalApk(Context context) {
        return new File(AppUpdater.updateDirectory(context), UpdateDownloadService.FINAL_FILE_NAME);
    }

    public static VerificationResult verifyDownloadedApk(Context context, File apk) {
        if (apk == null || !apk.isFile() || apk.length() <= 0L) {
            return invalid("Downloaded APK is missing or empty");
        }

        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null) return invalid("Android could not read the downloaded APK");

        if (!context.getPackageName().equals(archive.packageName)) {
            return invalid("Downloaded APK is not the installed Smart S Launcher package");
        }

        final PackageInfo installed;
        try {
            installed = pm.getPackageInfo(context.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException e) {
            return invalid("Installed Smart S Launcher package could not be verified");
        }

        Set<String> archiveSigners = signerDigests(archive);
        Set<String> installedSigners = signerDigests(installed);
        archiveSigners.retainAll(installedSigners);
        if (archiveSigners.isEmpty()) {
            return invalid("Downloaded APK signing certificate does not match installed Smart S Launcher");
        }

        long candidateCode = versionCode(archive);
        long installedCode = versionCode(installed);
        String versionName = archive.versionName == null ? "" : archive.versionName;
        if (candidateCode <= installedCode) {
            return new VerificationResult(false,
                    "Release build is not newer than installed Smart S Launcher",
                    versionName, candidateCode);
        }
        return new VerificationResult(true, "Verified", versionName, candidateCode);
    }

    public static boolean canRequestPackageInstalls(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || context.getPackageManager().canRequestPackageInstalls();
    }

    public static Intent unknownSourcesSettingsIntent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return new Intent(Settings.ACTION_SECURITY_SETTINGS);
        }
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:" + context.getPackageName()));
    }

    public static Intent installIntent(Context context) {
        File apk = finalApk(context);
        Uri uri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".updates", apk);
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static VerificationResult invalid(String message) {
        return new VerificationResult(false, message, "", 0L);
    }

    private static long versionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return info.getLongVersionCode();
        //noinspection deprecation
        return info.versionCode;
    }

    private static Set<String> signerDigests(PackageInfo info) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info.signingInfo == null) return new HashSet<>();
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            //noinspection deprecation
            signatures = info.signatures;
        }

        Set<String> values = new HashSet<>();
        if (signatures == null) return values;
        for (Signature signature : signatures) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(signature.toByteArray());
                StringBuilder hex = new StringBuilder(64);
                for (byte value : digest) {
                    hex.append(String.format(Locale.ROOT, "%02x", value));
                }
                values.add(hex.toString());
            } catch (Exception ignored) {
                // A missing SHA-256 implementation causes verification to fail closed.
            }
        }
        return values;
    }
}
