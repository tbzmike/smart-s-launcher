package fr.neamar.kiss.update;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.neamar.kiss.BuildConfig;

/**
 * GitHub Releases updater for Smart S Launcher.
 *
 * Automatic mode intentionally stops at downloading the APK: Android's package installer still
 * requires user approval. Unsigned release assets are never selected.
 */
public final class AppUpdater {
    public static final String PREF_AUTO_UPDATE = "smart-auto-update";

    private static final String PREF_LAST_CHECK_MS = "smart-update-last-check-ms";
    private static final String PREF_DOWNLOAD_VERSION = "smart-update-download-version";
    private static final String PREF_DOWNLOAD_ID = "smart-update-download-id";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final String RELEASE_API =
            "https://api.github.com/repos/tbzmike/smart-s-launcher/releases/latest";
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

        prefs.edit().putLong(PREF_LAST_CHECK_MS, now).apply();
        checkForUpdates(app, false);
    }

    public static void checkForUpdates(Context context, boolean userInitiated) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                ReleaseInfo release = fetchLatestRelease();
                if (compareVersions(release.version, BuildConfig.VERSION_NAME) <= 0) {
                    if (userInitiated) {
                        postToast(app, "Smart S Launcher " + BuildConfig.VERSION_NAME + " is already up to date");
                    }
                    return;
                }

                ApkAsset asset = selectCompatibleApk(release.assets);
                if (asset == null) {
                    if (userInitiated) {
                        postToast(app, "Update " + release.version + " exists, but it has no compatible signed APK");
                    }
                    return;
                }

                if (userInitiated && context instanceof Activity) {
                    mainHandler().post(() -> showUpdateDialog((Activity) context, release, asset));
                } else if (userInitiated) {
                    postToast(app, "Update " + release.version + " is available");
                } else {
                    enqueueIfNeeded(app, release.version, asset);
                }
            } catch (Exception e) {
                if (userInitiated) postToast(app, "Update check failed: " + safeMessage(e));
            }
        });
    }

    private static ReleaseInfo fetchLatestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Smart-S-Launcher/" + BuildConfig.VERSION_NAME);
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String body = readFully(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("GitHub returned HTTP " + code);
        }

        JSONObject json = new JSONObject(body);
        String tag = json.optString("tag_name", "");
        if (tag.isEmpty()) throw new IllegalStateException("Latest release has no version tag");
        JSONArray assetsJson = json.optJSONArray("assets");
        ApkAsset[] assets = assetsJson == null ? new ApkAsset[0] : new ApkAsset[assetsJson.length()];
        if (assetsJson != null) {
            for (int i = 0; i < assetsJson.length(); i++) {
                JSONObject asset = assetsJson.getJSONObject(i);
                assets[i] = new ApkAsset(
                        asset.optString("name", ""),
                        asset.optString("browser_download_url", ""));
            }
        }
        return new ReleaseInfo(normalizeVersion(tag), assets);
    }

    private static ApkAsset selectCompatibleApk(ApkAsset[] assets) {
        ApkAsset releaseFallback = null;
        for (ApkAsset asset : assets) {
            String name = asset.name.toLowerCase(Locale.ROOT);
            if (!name.endsWith(".apk") || asset.url.isEmpty() || name.contains("unsigned")) continue;
            if (BuildConfig.DEBUG && "app-debug.apk".equals(name)) return asset;
            if (!BuildConfig.DEBUG && "app-release.apk".equals(name)) return asset;
            if (!BuildConfig.DEBUG && name.contains("release")) releaseFallback = asset;
        }
        return releaseFallback;
    }

    private static void showUpdateDialog(Activity activity, ReleaseInfo release, ApkAsset asset) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity)
                .setTitle("Smart S Launcher update")
                .setMessage("Version " + release.version + " is available. Download it now?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Download", (dialog, which) ->
                        EXECUTOR.execute(() -> enqueueDownload(activity.getApplicationContext(), release.version, asset)))
                .show();
    }

    private static void enqueueIfNeeded(Context context, String version, ApkAsset asset) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String storedVersion = prefs.getString(PREF_DOWNLOAD_VERSION, "");
        long storedId = prefs.getLong(PREF_DOWNLOAD_ID, -1L);
        if (version.equals(storedVersion) && storedId >= 0L
                && isDownloadActiveOrSuccessful(context, storedId)) {
            return;
        }
        enqueueDownload(context, version, asset);
    }

    private static boolean isDownloadActiveOrSuccessful(Context context, long id) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return false;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (statusIndex < 0) return false;
            int status = cursor.getInt(statusIndex);
            return status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PAUSED
                    || status == DownloadManager.STATUS_SUCCESSFUL;
        }
    }

    private static void enqueueDownload(Context context, String version, ApkAsset asset) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            postToast(context, "Android Download Manager is unavailable");
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(asset.url));
        request.setTitle("Smart S Launcher " + version);
        request.setDescription("Launcher update APK");
        request.setMimeType("application/vnd.android.package-archive");
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(false);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        long id = manager.enqueue(request);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_DOWNLOAD_VERSION, version)
                .putLong(PREF_DOWNLOAD_ID, id)
                .apply();
        postToast(context, "Downloading Smart S Launcher " + version);
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

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static void postToast(Context context, String message) {
        mainHandler().post(() -> Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    private static final class ReleaseInfo {
        final String version;
        final ApkAsset[] assets;

        ReleaseInfo(String version, ApkAsset[] assets) {
            this.version = version;
            this.assets = assets;
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
