package fr.neamar.kiss.update;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import fr.neamar.kiss.BuildConfig;

/**
 * Last-resort updater path that delegates network I/O to Android's DownloadManager.
 *
 * This is intentionally separate from Smart S Launcher's own HttpURLConnection path. If the
 * launcher's long-lived process has a stale/broken DNS route, Android's system download service
 * can still fetch the fixed APK attached to the latest GitHub Release. The APK is then subjected
 * to the same package/signature/version verification before it is accepted.
 */
final class SystemReleaseFallback {
    static final class Result {
        final boolean upToDate;
        final String versionName;
        final long versionCode;
        final long size;
        final String sha256;
        final String assetName;
        final String assetUrl;

        Result(boolean upToDate, String versionName, long versionCode, long size,
               String sha256, String assetName, String assetUrl) {
            this.upToDate = upToDate;
            this.versionName = versionName == null ? "" : versionName;
            this.versionCode = Math.max(0L, versionCode);
            this.size = Math.max(0L, size);
            this.sha256 = sha256 == null ? "" : sha256;
            this.assetName = assetName == null ? "" : assetName;
            this.assetUrl = assetUrl == null ? "" : assetUrl;
        }
    }

    private static final long TIMEOUT_MS = 120_000L;
    private static volatile long activeDownloadId = -1L;

    private SystemReleaseFallback() {
    }

    static Result checkLatest(Context context) throws Exception {
        Context app = context.getApplicationContext();
        DownloadManager manager =
                (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) throw new IOException("Android DownloadManager is unavailable");

        String assetName = AppUpdater.expectedReleaseAssetName(
                BuildConfig.VERSION_NAME, BuildConfig.DEBUG);
        String assetUrl = AppUpdater.latestStableAssetUrl(BuildConfig.DEBUG);

        File downloads = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null) throw new IOException("Android external download directory is unavailable");
        File folder = new File(downloads, "smart_s_updates");
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Unable to create Smart S update download directory");
        }
        File systemFile = new File(folder, assetName);
        if (systemFile.exists() && !systemFile.delete()) {
            throw new IOException("Unable to clear previous system-downloaded update");
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(assetUrl))
                .setTitle("Smart S Launcher update check")
                .setDescription("Checking the latest verified GitHub release")
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(
                        app, Environment.DIRECTORY_DOWNLOADS,
                        "smart_s_updates/" + assetName);

        long id = manager.enqueue(request);
        activeDownloadId = id;
        long started = System.currentTimeMillis();
        long total = 0L;
        long downloaded = 0L;

        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("System update check was interrupted");
                }
                if (System.currentTimeMillis() - started > TIMEOUT_MS) {
                    throw new IOException("Android system downloader timed out");
                }

                try (Cursor cursor = manager.query(
                        new DownloadManager.Query().setFilterById(id))) {
                    if (cursor == null || !cursor.moveToFirst()) {
                        throw new IOException("Android system downloader lost the update request");
                    }
                    int status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    downloaded = Math.max(0L, cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
                    total = Math.max(0L, cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));

                    AppUpdater.updateFromService(app, new AppUpdater.UpdateState(
                            AppUpdater.Phase.DOWNLOADING,
                            "Direct GitHub connection failed — Android is checking the latest release…",
                            downloaded, total, 0L, "", assetUrl, "", assetName, "", 0L), true);

                    if (status == DownloadManager.STATUS_SUCCESSFUL) break;
                    if (status == DownloadManager.STATUS_FAILED) {
                        int reason = cursor.getInt(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                        throw new IOException("Android system downloader failed (reason " + reason + ")");
                    }
                }

                Thread.sleep(300L);
            }

            if (!systemFile.isFile() || systemFile.length() <= 0L) {
                throw new IOException("Android reported success but the release APK is missing");
            }

            File finalApk = UpdateInstaller.finalApk(app);
            if (finalApk.exists() && !finalApk.delete()) {
                throw new IOException("Unable to replace a previous staged update");
            }
            copy(systemFile, finalApk);

            UpdateInstaller.VerificationResult verification =
                    UpdateInstaller.verifyDownloadedApk(app, finalApk);
            long installedCode = AppUpdater.installedVersionCode(app);

            if (verification.valid) {
                String sha = AppUpdater.sha256Of(finalApk);
                return new Result(false, verification.versionName, verification.versionCode,
                        finalApk.length(), sha, assetName, assetUrl);
            }

            if (verification.versionCode > 0L && verification.versionCode <= installedCode
                    && "Release build is not newer than installed Smart S Launcher"
                    .equals(verification.message)) {
                String sha = AppUpdater.sha256Of(finalApk);
                finalApk.delete();
                return new Result(true, verification.versionName, verification.versionCode,
                        systemFile.length(), sha, assetName, assetUrl);
            }

            finalApk.delete();
            throw new IOException(verification.message);
        } finally {
            activeDownloadId = -1L;
            manager.remove(id);
            systemFile.delete();
        }
    }

    static boolean cancel(Context context) {
        long id = activeDownloadId;
        if (id < 0L) return false;
        DownloadManager manager =
                (DownloadManager) context.getApplicationContext()
                        .getSystemService(Context.DOWNLOAD_SERVICE);
        activeDownloadId = -1L;
        return manager != null && manager.remove(id) > 0;
    }

    private static void copy(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
    }
}
