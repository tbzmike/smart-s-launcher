package fr.neamar.kiss.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.neamar.kiss.BuildConfig;

/**
 * Release-based Smart S Launcher updater.
 *
 * This follows the verified MarkVault update design: only the public latest stable GitHub Release
 * is trusted, the production APK asset must expose an exact SHA-256 digest and size, downloads are
 * resumable, and package/signing/version checks run before Android is ever asked to install it.
 */
public final class AppUpdater {
    public static final String PREF_AUTO_UPDATE = "smart-auto-update";

    static final String PREFS_NAME = "smart_s_app_update";
    private static final String PREF_LAST_CHECK_MS = "smart-update-last-check-ms";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    // MarkVault's working updater relies on a stable APK asset attached to the newest GitHub
    // Release. Smart S keeps its versioned release pages, but each newest release now also carries
    // fixed updater asset names plus this manifest. The GitHub API remains only a metadata fallback.
    static final String PRIMARY_MANIFEST_URL =
            "https://github.com/tbzmike/smart-s-launcher/releases/latest/download/latest-green.json";
    static final String MIRROR_MANIFEST_URL =
            "https://raw.githubusercontent.com/tbzmike/smart-s-launcher/updater-channel/latest-green.json";
    static final String RELEASE_API =
            "https://api.github.com/repos/tbzmike/smart-s-launcher/releases/latest";
    static final String STABLE_DEBUG_ASSET = "SmartSLauncher-debug.apk";
    static final String STABLE_RELEASE_ASSET = "SmartSLauncher.apk";
    private static final String LATEST_RELEASE_DOWNLOAD_PREFIX =
            "https://github.com/tbzmike/smart-s-launcher/releases/latest/download/";
    private static final String EXPECTED_DOWNLOAD_HOST = "github.com";
    private static final String EXPECTED_DOWNLOAD_PATH_PREFIX =
            "/tbzmike/smart-s-launcher/releases/download/";

    private static final String KEY_PHASE = "phase";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_BYTES = "bytes";
    private static final String KEY_TOTAL = "total";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_UPDATED = "updated";
    private static final String KEY_URL = "url";
    private static final String KEY_SHA = "sha";
    private static final String KEY_ASSET = "asset";
    private static final String KEY_VERSION_NAME = "version_name";
    private static final String KEY_VERSION_CODE = "version_code";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    enum Phase {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        AVAILABLE,
        DOWNLOADING,
        WAITING_NETWORK,
        VERIFYING,
        READY_TO_INSTALL,
        PAUSED_SYSTEM,
        CANCELED,
        ERROR
    }

    static final class UpdateState {
        final Phase phase;
        final String message;
        final long bytesDownloaded;
        final long totalBytes;
        final long bytesPerSecond;
        final String releaseUpdatedAt;
        final String assetUrl;
        final String expectedSha256;
        final String assetName;
        final String candidateVersionName;
        final long candidateVersionCode;

        UpdateState(Phase phase, String message, long bytesDownloaded, long totalBytes,
                    long bytesPerSecond, String releaseUpdatedAt, String assetUrl,
                    String expectedSha256, String assetName, String candidateVersionName,
                    long candidateVersionCode) {
            this.phase = phase;
            this.message = safe(message);
            this.bytesDownloaded = Math.max(0L, bytesDownloaded);
            this.totalBytes = Math.max(0L, totalBytes);
            this.bytesPerSecond = Math.max(0L, bytesPerSecond);
            this.releaseUpdatedAt = safe(releaseUpdatedAt);
            this.assetUrl = safe(assetUrl);
            this.expectedSha256 = safe(expectedSha256);
            this.assetName = safe(assetName);
            this.candidateVersionName = safe(candidateVersionName);
            this.candidateVersionCode = Math.max(0L, candidateVersionCode);
        }

        static UpdateState idle() {
            return new UpdateState(Phase.IDLE, "", 0L, 0L, 0L,
                    "", "", "", "", "", 0L);
        }

        boolean active() {
            return phase == Phase.DOWNLOADING || phase == Phase.WAITING_NETWORK
                    || phase == Phase.VERIFYING;
        }

        UpdateState withTransfer(Phase newPhase, String newMessage, long bytes, long speed) {
            return new UpdateState(newPhase, newMessage, bytes, totalBytes, speed,
                    releaseUpdatedAt, assetUrl, expectedSha256, assetName,
                    candidateVersionName, candidateVersionCode);
        }

        UpdateState withReady(String versionName, long versionCode) {
            return new UpdateState(Phase.READY_TO_INSTALL,
                    "Smart S Launcher " + versionName + " is verified and ready to install.",
                    totalBytes, totalBytes, 0L, releaseUpdatedAt, assetUrl,
                    expectedSha256, assetName, versionName, versionCode);
        }
    }

    private static final class ReleaseAsset {
        final String version;
        final long versionCode;
        final String name;
        final String downloadUrl;
        final long size;
        final String sha256;
        final String updatedAt;

        ReleaseAsset(String version, long versionCode, String name, String downloadUrl, long size,
                     String sha256, String updatedAt) {
            this.version = version;
            this.versionCode = Math.max(0L, versionCode);
            this.name = name;
            this.downloadUrl = downloadUrl;
            this.size = size;
            this.sha256 = sha256;
            this.updatedAt = updatedAt;
        }
    }

    private AppUpdater() {
    }

    public static void maybeAutoUpdate(Context context) {
        Context app = context.getApplicationContext();
        reconcileInstalledUpdate(app);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        if (!prefs.getBoolean(PREF_AUTO_UPDATE, false)) return;

        long now = System.currentTimeMillis();
        long lastCheck = prefs.getLong(PREF_LAST_CHECK_MS, 0L);
        if (now - lastCheck < AUTO_CHECK_INTERVAL_MS) {
            resumePendingDownload(app);
            return;
        }
        prefs.edit().putLong(PREF_LAST_CHECK_MS, now).apply();
        checkForUpdates(app, false);
    }

    public static void checkForUpdates(Context context, boolean userInitiated) {
        final Context app = context.getApplicationContext();
        final Activity activity = context instanceof Activity ? (Activity) context : null;

        setState(app, new UpdateState(
                Phase.CHECKING,
                "Checking the latest successful Smart S Launcher release…",
                0L, 0L, 0L, "", "", "", "", "", 0L));

        EXECUTOR.execute(() -> {
            try {
                ReleaseAsset release = fetchLatestRelease(app);
                long installedCode = installedVersionCode(app);
                boolean manifestHasCode = release.versionCode > 0L;
                boolean notNewer = manifestHasCode
                        ? release.versionCode <= installedCode
                        : compareVersions(release.version, BuildConfig.VERSION_NAME) <= 0;
                if (notNewer) {
                    clearDownloadedFiles(app);
                    setState(app, new UpdateState(
                            Phase.UP_TO_DATE,
                            "Smart S Launcher " + BuildConfig.VERSION_NAME
                                    + " is already the latest successful release.",
                            release.size, release.size, 0L, release.updatedAt,
                            release.downloadUrl, release.sha256, release.name, "", 0L));
                    if (userInitiated) postToast(app,
                            "Smart S Launcher " + BuildConfig.VERSION_NAME + " is up to date");
                    return;
                }

                UpdateState previous = current(app);
                if (!previous.expectedSha256.isEmpty()
                        && !previous.expectedSha256.equalsIgnoreCase(release.sha256)) {
                    clearDownloadedFiles(app);
                }

                String installedDigest = sha256Of(new File(app.getApplicationInfo().sourceDir));
                if (installedDigest.equalsIgnoreCase(release.sha256)) {
                    clearDownloadedFiles(app);
                    setState(app, new UpdateState(
                            Phase.UP_TO_DATE,
                            "Smart S Launcher " + BuildConfig.VERSION_NAME
                                    + " is already the latest successful release.",
                            release.size, release.size, 0L, release.updatedAt,
                            release.downloadUrl, release.sha256, release.name, "", 0L));
                    if (userInitiated) postToast(app,
                            "Smart S Launcher " + BuildConfig.VERSION_NAME + " is up to date");
                    return;
                }

                File downloaded = UpdateInstaller.finalApk(app);
                boolean downloadedMatches = downloaded.isFile()
                        && downloaded.length() == release.size
                        && sha256Of(downloaded).equalsIgnoreCase(release.sha256);
                if (downloadedMatches) {
                    UpdateInstaller.VerificationResult verification =
                            UpdateInstaller.verifyDownloadedApk(app, downloaded);
                    if (verification.valid) {
                        UpdateState ready = new UpdateState(
                                Phase.READY_TO_INSTALL,
                                "Smart S Launcher " + verification.versionName
                                        + " is already downloaded, verified, and ready to install.",
                                release.size, release.size, 0L, release.updatedAt,
                                release.downloadUrl, release.sha256, release.name,
                                verification.versionName, verification.versionCode);
                        setState(app, ready);
                        if (userInitiated) {
                            mainHandler().post(() -> installReadyUpdate(context));
                        }
                        return;
                    }
                    downloaded.delete();
                }

                UpdateState available = new UpdateState(
                        Phase.AVAILABLE,
                        "A newer successful Smart S Launcher release (" + release.version
                                + ") is available.",
                        0L, release.size, 0L, release.updatedAt, release.downloadUrl,
                        release.sha256, release.name, release.version, 0L);
                setState(app, available);

                if (userInitiated && activity != null) {
                    mainHandler().post(() -> showUpdateDialog(activity, available));
                } else if (userInitiated) {
                    startDownload(app);
                } else if (PreferenceManager.getDefaultSharedPreferences(app)
                        .getBoolean(PREF_AUTO_UPDATE, false)) {
                    startDownload(app);
                }
            } catch (Throwable directFailure) {
                // Smart S Launcher is a long-lived HOME process. If its own DNS/socket path is
                // stale or blocked after a network handover, retry through Android's system
                // DownloadManager against a fixed asset on the same GitHub Release page.
                try {
                    SystemReleaseFallback.Result fallback =
                            SystemReleaseFallback.checkLatest(app);
                    if (fallback.upToDate) {
                        clearDownloadedFiles(app);
                        setState(app, new UpdateState(
                                Phase.UP_TO_DATE,
                                "Smart S Launcher " + BuildConfig.VERSION_NAME
                                        + " is already the latest successful release.",
                                fallback.size, fallback.size, 0L, "",
                                fallback.assetUrl, fallback.sha256, fallback.assetName,
                                "", 0L));
                        if (userInitiated) {
                            postToast(app, "Smart S Launcher "
                                    + BuildConfig.VERSION_NAME + " is up to date");
                        }
                    } else {
                        setState(app, new UpdateState(
                                Phase.READY_TO_INSTALL,
                                "Smart S Launcher " + fallback.versionName
                                        + " was downloaded by Android, verified, and is ready to install.",
                                fallback.size, fallback.size, 0L, "",
                                fallback.assetUrl, fallback.sha256, fallback.assetName,
                                fallback.versionName, fallback.versionCode));
                        if (userInitiated) {
                            mainHandler().post(() -> installReadyUpdate(context));
                        }
                    }
                } catch (Throwable fallbackFailure) {
                    String reason = "Direct GitHub: " + safeMessage(directFailure)
                            + "; Android system fallback: " + safeMessage(fallbackFailure)
                            + "; " + UpdateNetwork.describe(app);
                    setState(app, new UpdateState(
                            Phase.ERROR, "Update check failed: " + reason,
                            0L, 0L, 0L, "", "", "", "", "", 0L));
                    if (userInitiated) postToast(app, "Update check failed: " + reason);
                }
            }
        });
    }

    public static void startDownload(Context context) {
        Context app = context.getApplicationContext();
        UpdateState state = current(app);
        if (state.assetUrl.isEmpty() || state.expectedSha256.length() != 64
                || state.totalBytes <= 0L) {
            setState(app, state.withTransfer(
                    Phase.ERROR, "No verified release is selected. Check for updates again.", 0L, 0L));
            postToast(app, "Check for updates again before downloading");
            return;
        }

        setState(app, state.withTransfer(
                Phase.DOWNLOADING, "Preparing Smart S Launcher update download…",
                Math.min(state.bytesDownloaded, state.totalBytes), 0L));
        Intent intent = new Intent(app, UpdateDownloadService.class)
                .setAction(UpdateDownloadService.ACTION_START_OR_RESUME);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(app, intent);
            } else {
                app.startService(intent);
            }
        } catch (RuntimeException e) {
            setState(app, state.withTransfer(
                    Phase.ERROR, "Unable to start update download: " + safeMessage(e), 0L, 0L));
            postToast(app, "Unable to start update download");
        }
    }

    public static void cancelDownload(Context context) {
        Context app = context.getApplicationContext();
        SystemReleaseFallback.cancel(app);
        Intent intent = new Intent(app, UpdateDownloadService.class)
                .setAction(UpdateDownloadService.ACTION_CANCEL);
        try {
            app.startService(intent);
        } catch (RuntimeException e) {
            clearDownloadedFiles(app);
            UpdateState state = current(app);
            setState(app, state.withTransfer(Phase.CANCELED, "Update download canceled.", 0L, 0L));
        }
    }

    public static void resumePendingDownload(Context context) {
        Context app = context.getApplicationContext();
        UpdateState state = current(app);
        if (!state.active() && state.phase != Phase.PAUSED_SYSTEM) return;
        if (state.assetUrl.isEmpty() || state.expectedSha256.length() != 64) return;

        Intent intent = new Intent(app, UpdateDownloadService.class)
                .setAction(UpdateDownloadService.ACTION_START_OR_RESUME);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(app, intent);
            } else {
                app.startService(intent);
            }
        } catch (RuntimeException ignored) {
            // Android can temporarily block foreground-service starts. The persisted partial
            // download remains resumable the next time the launcher is visibly foreground.
        }
    }

    public static void installReadyUpdate(Context context) {
        Context app = context.getApplicationContext();
        File apk = UpdateInstaller.finalApk(app);
        UpdateInstaller.VerificationResult verification =
                UpdateInstaller.verifyDownloadedApk(app, apk);
        if (!verification.valid) {
            postToast(app, verification.message);
            return;
        }

        if (!UpdateInstaller.canRequestPackageInstalls(app)) {
            Intent settings = UpdateInstaller.unknownSourcesSettingsIntent(app);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                app.startActivity(settings);
                postToast(app, "Allow Smart S Launcher to install updates, then tap Install again");
            } catch (RuntimeException e) {
                postToast(app, "Android could not open the install permission screen");
            }
            return;
        }

        try {
            app.startActivity(UpdateInstaller.installIntent(app));
        } catch (RuntimeException e) {
            postToast(app, "Unable to open Android package installer: " + safeMessage(e));
        }
    }

    public static String currentStatus(Context context) {
        UpdateState state = current(context.getApplicationContext());
        if (state.message.isEmpty()) return "No update check has run yet.";
        if (state.totalBytes > 0L && state.active()) {
            long percent = Math.min(100L, (state.bytesDownloaded * 100L) / state.totalBytes);
            return state.message + " " + percent + "%";
        }
        return state.message;
    }

    private static void showUpdateDialog(Activity activity, UpdateState state) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String version = state.candidateVersionName.isEmpty()
                ? "a newer version" : state.candidateVersionName;
        new AlertDialog.Builder(activity)
                .setTitle("Smart S Launcher update")
                .setMessage("Verified production release " + version
                        + " is available. Download it now?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Download",
                        (dialog, which) -> startDownload(activity.getApplicationContext()))
                .show();
    }

    static UpdateState current(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Phase phase;
        try {
            phase = Phase.valueOf(prefs.getString(KEY_PHASE, Phase.IDLE.name()));
        } catch (RuntimeException e) {
            phase = Phase.IDLE;
        }
        return new UpdateState(
                phase,
                prefs.getString(KEY_MESSAGE, ""),
                prefs.getLong(KEY_BYTES, 0L),
                prefs.getLong(KEY_TOTAL, 0L),
                0L,
                prefs.getString(KEY_UPDATED, ""),
                prefs.getString(KEY_URL, ""),
                prefs.getString(KEY_SHA, ""),
                prefs.getString(KEY_ASSET, ""),
                prefs.getString(KEY_VERSION_NAME, ""),
                prefs.getLong(KEY_VERSION_CODE, 0L));
    }

    static void updateFromService(Context context, UpdateState state, boolean persist) {
        if (persist) setState(context.getApplicationContext(), state);
    }

    static File updateDirectory(Context context) {
        File dir = new File(context.getFilesDir(), "app_updates");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static void clearDownloadedFiles(Context context) {
        File dir = updateDirectory(context);
        new File(dir, UpdateDownloadService.PART_FILE_NAME).delete();
        new File(dir, UpdateDownloadService.FINAL_FILE_NAME).delete();
    }

    private static void setState(Context context, UpdateState state) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_PHASE, state.phase.name())
                .putString(KEY_MESSAGE, state.message)
                .putLong(KEY_BYTES, state.bytesDownloaded)
                .putLong(KEY_TOTAL, state.totalBytes)
                .putLong(KEY_SPEED, 0L)
                .putString(KEY_UPDATED, state.releaseUpdatedAt)
                .putString(KEY_URL, state.assetUrl)
                .putString(KEY_SHA, state.expectedSha256)
                .putString(KEY_ASSET, state.assetName)
                .putString(KEY_VERSION_NAME, state.candidateVersionName)
                .putLong(KEY_VERSION_CODE, state.candidateVersionCode)
                .apply();
    }

    private static void reconcileInstalledUpdate(Context context) {
        UpdateState state = current(context);
        if (state.phase != Phase.READY_TO_INSTALL || state.candidateVersionCode <= 0L) return;
        if (installedVersionCode(context) < state.candidateVersionCode) return;

        clearDownloadedFiles(context);
        setState(context, new UpdateState(
                Phase.UP_TO_DATE,
                "Smart S Launcher " + BuildConfig.VERSION_NAME + " was updated successfully.",
                0L, 0L, 0L, state.releaseUpdatedAt, state.assetUrl,
                state.expectedSha256, state.assetName, "", 0L));
    }

    static long installedVersionCode(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return info.getLongVersionCode();
            //noinspection deprecation
            return info.versionCode;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return 0L;
        }
    }

    private static ReleaseAsset fetchLatestRelease(Context context) throws Exception {
        Exception primaryFailure = null;
        try {
            return fetchReleaseManifest(context, PRIMARY_MANIFEST_URL);
        } catch (Exception e) {
            primaryFailure = e;
        }

        Exception mirrorFailure = null;
        try {
            return fetchReleaseManifest(context, MIRROR_MANIFEST_URL);
        } catch (Exception e) {
            mirrorFailure = e;
        }

        try {
            return fetchLatestReleaseFromApi(context);
        } catch (Exception apiFailure) {
            IOException combined = new IOException(
                    "Unable to reach verified Smart S Launcher update metadata. "
                            + "Primary GitHub release: " + safeMessage(primaryFailure)
                            + "; mirror: " + safeMessage(mirrorFailure)
                            + "; API fallback: " + safeMessage(apiFailure));
            combined.addSuppressed(apiFailure);
            throw combined;
        }
    }

    private static ReleaseAsset fetchReleaseManifest(Context context, String manifestUrl) throws Exception {
        HttpURLConnection connection = openMetadataConnection(context, manifestUrl, "application/json");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("GitHub manifest returned HTTP " + code);
            }

            JSONObject root = new JSONObject(readAll(connection));
            String version = normalizeVersion(root.optString("version", ""));
            long versionCode = root.optLong("versionCode", 0L);
            if (version.isEmpty() || versionCode <= 0L) {
                throw new IOException("Update manifest has no valid version/versionCode");
            }

            String prefix = BuildConfig.DEBUG ? "debug" : "release";
            String expectedAssetName = expectedReleaseAssetName(version, BuildConfig.DEBUG);
            String name = root.optString(prefix + "ApkName", "");
            String downloadUrl = root.optString(prefix + "ApkUrl", "");
            String sha = root.optString(prefix + "ApkSha256", "")
                    .toLowerCase(Locale.ROOT);
            long size = root.optLong(prefix + "ApkSize", -1L);

            if (!expectedAssetName.equals(name)) {
                throw new IOException("Update manifest selected an unexpected APK asset");
            }
            if (size <= 0L) throw new IOException("Update manifest APK size is invalid");
            if (!sha.matches("[0-9a-f]{64}")) {
                throw new IOException("Update manifest has no valid APK SHA-256");
            }
            validateDownloadUrl(downloadUrl, expectedAssetName);

            return new ReleaseAsset(
                    version, versionCode, name, downloadUrl, size, sha,
                    root.optString("publishedAt", root.optString("sha", "")));
        } finally {
            connection.disconnect();
        }
    }

    private static ReleaseAsset fetchLatestReleaseFromApi(Context context) throws Exception {
        HttpURLConnection connection = openMetadataConnection(
                context, RELEASE_API, "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("GitHub API returned HTTP " + code);

            JSONObject root = new JSONObject(readAll(connection));
            if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
                throw new IOException("Latest release is not a published stable release");
            }

            String version = normalizeVersion(root.optString("tag_name", ""));
            if (version.isEmpty()) throw new IOException("Latest release has no version tag");
            String expectedAssetName = expectedReleaseAssetName(version, BuildConfig.DEBUG);

            JSONArray assets = root.optJSONArray("assets");
            if (assets == null) throw new IOException("Release has no APK assets");
            JSONObject selected = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject candidate = assets.optJSONObject(i);
                if (candidate != null
                        && expectedAssetName.equals(candidate.optString("name"))
                        && "uploaded".equals(candidate.optString("state"))) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                throw new IOException("Latest release does not contain " + expectedAssetName);
            }

            long size = selected.optLong("size", -1L);
            if (size <= 0L) throw new IOException("Release APK has an invalid size");
            String rawDigest = selected.optString("digest", "");
            if (!rawDigest.toLowerCase(Locale.ROOT).startsWith("sha256:")) {
                throw new IOException("Release APK has no SHA-256 digest");
            }
            String sha = rawDigest.substring(rawDigest.indexOf(':') + 1)
                    .toLowerCase(Locale.ROOT);
            if (!sha.matches("[0-9a-f]{64}")) {
                throw new IOException("Release APK has an invalid SHA-256 digest");
            }

            String downloadUrl = selected.optString("browser_download_url", "");
            validateDownloadUrl(downloadUrl, expectedAssetName);
            return new ReleaseAsset(version, 0L, expectedAssetName, downloadUrl, size, sha,
                    selected.optString("updated_at", ""));
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openMetadataConnection(
            Context context, String rawUrl, String accept) throws IOException {
        HttpURLConnection connection = UpdateNetwork.open(
                context.getApplicationContext(), rawUrl, 20_000, 20_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", "Smart-S-Launcher/" + BuildConfig.VERSION_NAME);
        connection.connect();
        return connection;
    }

    private static String readAll(HttpURLConnection connection) throws IOException {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(connection.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            return result.toString();
        }
    }

    private static void validateDownloadUrl(String raw, String assetName) throws IOException {
        Uri uri = Uri.parse(raw);
        boolean schemeOk = "https".equalsIgnoreCase(uri.getScheme());
        boolean hostOk = EXPECTED_DOWNLOAD_HOST.equalsIgnoreCase(uri.getHost());
        String path = uri.getPath() == null ? "" : uri.getPath();
        boolean pathOk = path.startsWith(EXPECTED_DOWNLOAD_PATH_PREFIX)
                && path.endsWith("/" + assetName);
        if (!schemeOk || !hostOk || !pathOk) {
            throw new IOException("Release APK URL is not an approved Smart S Launcher GitHub URL");
        }
    }

    static String sha256Of(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    static String expectedReleaseAssetName(String version, boolean debugBuild) {
        return debugBuild ? STABLE_DEBUG_ASSET : STABLE_RELEASE_ASSET;
    }

    static String latestStableAssetUrl(boolean debugBuild) {
        return LATEST_RELEASE_DOWNLOAD_PREFIX
                + (debugBuild ? STABLE_DEBUG_ASSET : STABLE_RELEASE_ASSET);
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

    static String normalizeVersion(String version) {
        String value = version == null ? "" : version.trim();
        while (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        return value;
    }

    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    private static void postToast(Context context, String message) {
        mainHandler().post(() -> Toast.makeText(
                context.getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    private static String safeMessage(Throwable t) {
        String message = t == null ? null : t.getMessage();
        return message == null || message.trim().isEmpty()
                ? (t == null ? "Unknown error" : t.getClass().getSimpleName()) : message;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
