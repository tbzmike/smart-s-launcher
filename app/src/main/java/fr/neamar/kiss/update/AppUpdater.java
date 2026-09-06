package fr.neamar.kiss.update;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.neamar.kiss.BuildConfig;

/**
 * In-app updater backed by the latest CI run that completed successfully.
 *
 * The CI workflow publishes a small manifest plus the debug APK to the stable latest-green
 * prerelease only after lint, unit tests and APK generation all pass. The app therefore never
 * installs an APK from a red, cancelled or still-running workflow. Android's package installer
 * remains the final authority and may require the user to approve installation from this source.
 */
public final class AppUpdater {
    public static final String PREF_AUTO_UPDATE = "smart-auto-update";

    private static final String PREF_LAST_CHECK_MS = "smart-update-last-check-ms";
    private static final String PREF_DOWNLOAD_VERSION = "smart-update-download-version";
    private static final String PREF_DOWNLOAD_ID = "smart-update-download-id";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    // This is deliberately served from github.com rather than api.github.com. The release is
    // replaced by CI only after the complete App testing workflow is green.
    private static final String GREEN_MANIFEST_URL =
            "https://github.com/tbzmike/smart-s-launcher/releases/download/latest-green/latest-green.json";
    private static final String EXPECTED_APK_HOST = "github.com";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    private AppUpdater() {
    }

    public static void maybeAutoUpdate(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        if (!prefs.getBoolean(PREF_AUTO_UPDATE, false)) return;

        long now = System.currentTimeMillis();
        long lastCheck = prefs.getLong(PREF_LAST_CHECK_MS, 0L);
        if (now - lastCheck < AUTO_CHECK_INTERVAL_MS) return;

        // Record the attempted automatic check so a broken/offline connection cannot create a
        // tight retry loop every time the launcher resumes.
        prefs.edit().putLong(PREF_LAST_CHECK_MS, now).apply();
        checkForUpdates(app, false);
    }

    public static void checkForUpdates(Context context, boolean userInitiated) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                BuildInfo build = fetchLatestGreenBuild();
                if (!isCompatibleVariant(build.variant)) {
                    if (userInitiated) {
                        postToast(app, "Latest green build is not compatible with this installed variant");
                    }
                    return;
                }
                if (compareVersions(build.version, BuildConfig.VERSION_NAME) <= 0) {
                    if (userInitiated) {
                        postToast(app, "Smart S Launcher " + BuildConfig.VERSION_NAME
                                + " is already on the latest green build");
                    }
                    return;
                }

                ApkAsset asset = new ApkAsset(build.apkName, build.apkUrl);
                if (userInitiated && context instanceof Activity) {
                    mainHandler().post(() -> showUpdateDialog((Activity) context, build, asset));
                } else if (userInitiated) {
                    enqueueDownload(app, build.version, asset);
                } else {
                    enqueueIfNeeded(app, build.version, asset);
                }
            } catch (Exception e) {
                if (userInitiated) {
                    postToast(app, "Update check failed: " + userFacingNetworkMessage(e));
                }
            }
        });
    }

    private static BuildInfo fetchLatestGreenBuild() throws Exception {
        HttpURLConnection connection = openConnection(GREEN_MANIFEST_URL);
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String body = readFully(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("GitHub returned HTTP " + code);
        }
        return parseBuildInfo(body);
    }

    /**
     * Parse only the fixed, CI-generated latest-green schema. A tiny strict parser keeps this
     * validation usable in ordinary JVM unit tests instead of depending on Android's org.json
     * runtime stubs. CI controls every value in this manifest; escaped string values are rejected.
     */
    static BuildInfo parseBuildInfo(String body) throws Exception {
        String version = normalizeVersion(jsonString(body, "version"));
        long runId = jsonLong(body, "runId");
        long runNumber = jsonLong(body, "runNumber");
        String sha = jsonString(body, "sha").trim();
        String variant = jsonString(body, "variant").trim().toLowerCase(Locale.ROOT);
        String apkName = jsonString(body, "apkName").trim();
        String apkUrl = jsonString(body, "apkUrl").trim();

        if (version.isEmpty()) throw new IllegalStateException("Green build manifest has no version");
        if (runId <= 0L || runNumber <= 0L) {
            throw new IllegalStateException("Green build manifest has no workflow identity");
        }
        if (sha.length() < 7) throw new IllegalStateException("Green build manifest has no commit SHA");
        if (!"debug".equals(variant)) {
            throw new IllegalStateException("Green build manifest has unsupported variant");
        }
        if (!"app-debug.apk".equals(apkName)) {
            throw new IllegalStateException("Green build manifest has unexpected APK name");
        }
        validateApkUrl(apkUrl);
        return new BuildInfo(version, runId, runNumber, sha, variant, apkName, apkUrl);
    }

    private static String jsonString(String body, String key) {
        if (body == null) throw new IllegalStateException("Green build manifest is empty");
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key)
                + "\\\"\\s*:\\s*\\\"([^\\\"\\\\]*)\\\"");
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("Green build manifest is missing " + key);
        }
        return matcher.group(1);
    }

    private static long jsonLong(String body, String key) {
        if (body == null) throw new IllegalStateException("Green build manifest is empty");
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key)
                + "\\\"\\s*:\\s*([0-9]+)");
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("Green build manifest is missing " + key);
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Green build manifest has invalid " + key, e);
        }
    }

    private static void validateApkUrl(String value) throws Exception {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalStateException("Green build APK URL is not HTTPS");
        }
        if (!EXPECTED_APK_HOST.equalsIgnoreCase(url.getHost())) {
            throw new IllegalStateException("Green build APK URL has unexpected host");
        }
        String expectedPrefix = "/tbzmike/smart-s-launcher/releases/download/latest-green/";
        if (!url.getPath().startsWith(expectedPrefix)) {
            throw new IllegalStateException("Green build APK URL has unexpected path");
        }
    }

    private static boolean isCompatibleVariant(String variant) {
        return BuildConfig.DEBUG && "debug".equals(variant);
    }

    private static HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, application/octet-stream;q=0.9, */*;q=0.8");
        connection.setRequestProperty("User-Agent", "Smart-S-Launcher/" + BuildConfig.VERSION_NAME);
        return connection;
    }

    private static void showUpdateDialog(Activity activity, BuildInfo build, ApkAsset asset) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity)
                .setTitle("Smart S Launcher update")
                .setMessage("Green App testing build " + build.version + " (#" + build.runNumber
                        + ") passed. Download and install it now?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Download", (dialog, which) -> EXECUTOR.execute(() ->
                        enqueueDownload(activity.getApplicationContext(), build.version, asset)))
                .show();
    }

    private static void enqueueIfNeeded(Context context, String version, ApkAsset asset) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String storedVersion = prefs.getString(PREF_DOWNLOAD_VERSION, "");
        long storedId = prefs.getLong(PREF_DOWNLOAD_ID, -1L);
        if (version.equals(storedVersion) && storedId >= 0L
                && isDownloadActiveOrSuccessful(context, storedId)) {
            if (isDownloadSuccessful(context, storedId)) installDownloadedApk(context, storedId);
            return;
        }
        enqueueDownload(context, version, asset);
    }

    private static boolean isDownloadActiveOrSuccessful(Context context, long id) {
        int status = downloadStatus(context, id);
        return status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PAUSED
                || status == DownloadManager.STATUS_SUCCESSFUL;
    }

    private static boolean isDownloadSuccessful(Context context, long id) {
        return downloadStatus(context, id) == DownloadManager.STATUS_SUCCESSFUL;
    }

    private static int downloadStatus(Context context, long id) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return -1;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return -1;
            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            return statusIndex < 0 ? -1 : cursor.getInt(statusIndex);
        }
    }

    private static void enqueueDownload(Context context, String version, ApkAsset asset) {
        try {
            validateApkUrl(asset.url);
        } catch (Exception e) {
            postToast(context, "Refusing invalid update download URL");
            return;
        }

        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            postToast(context, "Android Download Manager is unavailable");
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(asset.url));
        request.setTitle("Smart S Launcher " + version);
        request.setDescription("Verified green App testing APK");
        request.setMimeType("application/vnd.android.package-archive");
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(false);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        long id = manager.enqueue(request);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_DOWNLOAD_VERSION, version)
                .putLong(PREF_DOWNLOAD_ID, id)
                .apply();
        postToast(context, "Downloading green Smart S Launcher build " + version);
    }

    /** Called only by the private ACTION_DOWNLOAD_COMPLETE receiver. */
    static void onDownloadComplete(Context context, long completedId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        long expectedId = prefs.getLong(PREF_DOWNLOAD_ID, -1L);
        if (completedId < 0L || completedId != expectedId) return;
        if (!isDownloadSuccessful(context, completedId)) {
            postToast(context, "Smart S Launcher update download did not complete successfully");
            return;
        }
        installDownloadedApk(context, completedId);
    }

    private static void installDownloadedApk(Context context, long downloadId) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return;
        Uri apkUri = manager.getUriForDownloadedFile(downloadId);
        if (apkUri == null) {
            postToast(context, "Downloaded update APK is unavailable");
            return;
        }

        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(apkUri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(install);
        } catch (Exception e) {
            postToast(context, "Unable to open Android package installer: " + safeMessage(e));
        }
    }

    static int compareVersions(String left, String right) {
        int[] a = numericVersion(left);
        int[] b = numericVersion(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int[] numericVersion(String version) {
        String normalized = normalizeVersion(version);
        String[] parts = normalized.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceFirst("[^0-9].*$", "");
            if (digits.isEmpty()) {
                result[i] = 0;
            } else {
                try {
                    result[i] = Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {
                    result[i] = Integer.MAX_VALUE;
                }
            }
        }
        return result;
    }

    private static String normalizeVersion(String version) {
        String value = version == null ? "" : version.trim();
        while (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        return value;
    }

    private static String readFully(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String userFacingNetworkMessage(Exception e) {
        String message = safeMessage(e);
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("unable to resolve host") || lower.contains("unknownhost")) {
            return "GitHub cannot be reached. Check Internet/DNS and try again";
        }
        return message;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static void postToast(Context context, String message) {
        mainHandler().post(() -> Toast.makeText(
                context.getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    static final class BuildInfo {
        final String version;
        final long runId;
        final long runNumber;
        final String sha;
        final String variant;
        final String apkName;
        final String apkUrl;

        BuildInfo(String version, long runId, long runNumber, String sha, String variant,
                  String apkName, String apkUrl) {
            this.version = version;
            this.runId = runId;
            this.runNumber = runNumber;
            this.sha = sha;
            this.variant = variant;
            this.apkName = apkName;
            this.apkUrl = apkUrl;
        }
    }

    private static final class ApkAsset {
        final String name;
        final String url;

        ApkAsset(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
