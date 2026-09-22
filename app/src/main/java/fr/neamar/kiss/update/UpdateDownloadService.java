package fr.neamar.kiss.update;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;

/**
 * Resumable foreground downloader for verified Smart S Launcher production releases.
 *
 * Partial bytes are kept in private app storage and resumed with HTTP Range. The completed file
 * must match the GitHub release size + SHA-256 and then pass package/signature/version checks.
 */
public final class UpdateDownloadService extends Service {
    public static final String ACTION_START_OR_RESUME =
            "fr.neamar.kiss.update.START_OR_RESUME";
    public static final String ACTION_CANCEL =
            "fr.neamar.kiss.update.CANCEL";

    static final String PART_FILE_NAME = "SmartSLauncher.apk.part";
    static final String FINAL_FILE_NAME = "SmartSLauncher.apk";

    private static final String CHANNEL_ID = "smart_s_app_updates";
    private static final int NOTIFICATION_ID = 0x5331;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final long RETRY_DELAY_MS = 5_000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile Future<?> worker;
    private volatile HttpURLConnection activeConnection;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START_OR_RESUME : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelTransfer();
            return START_NOT_STICKY;
        }

        AppUpdater.UpdateState state = AppUpdater.current(this);
        startForegroundCompat(buildNotification(state.withTransfer(
                AppUpdater.Phase.DOWNLOADING,
                state.message.isEmpty() ? "Preparing Smart S Launcher update download…" : state.message,
                state.bytesDownloaded, 0L)));

        if (worker == null || worker.isDone()) {
            cancelRequested.set(false);
            worker = executor.submit(this::downloadLoop);
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Keep the resumable foreground transfer alive. Android may stop it later under system
        // policy; the persisted partial file will resume when Smart S Launcher returns.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        pauseForSystem();
    }

    private void downloadLoop() {
        try {
            AppUpdater.UpdateState metadata = AppUpdater.current(this);
            if (metadata.assetUrl.isEmpty() || metadata.expectedSha256.length() != 64
                    || metadata.totalBytes <= 0L) {
                fail("No verified release metadata is available");
                return;
            }

            File dir = AppUpdater.updateDirectory(this);
            File part = new File(dir, PART_FILE_NAME);
            File finalApk = new File(dir, FINAL_FILE_NAME);

            if (finalApk.isFile() && finalApk.length() == metadata.totalBytes) {
                String existingSha = AppUpdater.sha256Of(finalApk);
                if (existingSha.equalsIgnoreCase(metadata.expectedSha256)) {
                    UpdateInstaller.VerificationResult existing =
                            UpdateInstaller.verifyDownloadedApk(this, finalApk);
                    if (existing.valid) {
                        completeReady(metadata, existing);
                        return;
                    }
                }
                finalApk.delete();
            }

            if (part.length() > metadata.totalBytes) part.delete();
            int hashRetryCount = 0;

            while (!cancelRequested.get() && !Thread.currentThread().isInterrupted()) {
                long resumeFrom = part.isFile() ? part.length() : 0L;
                try {
                    HttpURLConnection connection = openDownloadConnection(metadata.assetUrl, resumeFrom);
                    activeConnection = connection;
                    int code = connection.getResponseCode();

                    if (resumeFrom > 0L && code == HttpURLConnection.HTTP_PARTIAL) {
                        validateContentRange(connection.getHeaderField("Content-Range"), resumeFrom);
                    } else if (resumeFrom > 0L && code == HttpURLConnection.HTTP_OK) {
                        connection.disconnect();
                        activeConnection = null;
                        if (!part.delete()) {
                            throw new IOException("Server cannot resume and partial file could not be reset");
                        }
                        continue;
                    } else if (resumeFrom == 0L && code != HttpURLConnection.HTTP_OK
                            && code != HttpURLConnection.HTTP_PARTIAL) {
                        throw new IOException("GitHub download returned HTTP " + code);
                    } else if (resumeFrom > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
                        throw new IOException("GitHub resume returned HTTP " + code);
                    }

                    long downloaded = resumeFrom;
                    long sampleAt = System.currentTimeMillis();
                    long sampleBytes = downloaded;
                    long lastUiAt = 0L;
                    long lastPersistAt = 0L;

                    try (java.io.InputStream input = connection.getInputStream();
                         FileOutputStream output = new FileOutputStream(part, resumeFrom > 0L)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            if (read == 0) continue;
                            if (cancelRequested.get() || Thread.currentThread().isInterrupted()) return;

                            output.write(buffer, 0, read);
                            downloaded += read;
                            if (downloaded > metadata.totalBytes) {
                                throw new IOException("Downloaded data exceeds advertised release size");
                            }

                            long now = System.currentTimeMillis();
                            if (now - lastUiAt >= 350L || downloaded == metadata.totalBytes) {
                                long elapsed = Math.max(1L, now - sampleAt);
                                long speed = Math.max(0L,
                                        (downloaded - sampleBytes) * 1000L / elapsed);
                                AppUpdater.UpdateState progress = new AppUpdater.UpdateState(
                                        AppUpdater.Phase.DOWNLOADING,
                                        "Downloading Smart S Launcher update…",
                                        downloaded, metadata.totalBytes, speed,
                                        metadata.releaseUpdatedAt, metadata.assetUrl,
                                        metadata.expectedSha256, metadata.assetName,
                                        metadata.candidateVersionName, metadata.candidateVersionCode);
                                boolean persist = now - lastPersistAt >= 1_000L
                                        || downloaded == metadata.totalBytes;
                                publish(progress, persist);
                                lastUiAt = now;
                                if (persist) lastPersistAt = now;
                                if (now - sampleAt >= 1_000L) {
                                    sampleAt = now;
                                    sampleBytes = downloaded;
                                }
                            }
                        }
                        output.getFD().sync();
                    } finally {
                        connection.disconnect();
                        activeConnection = null;
                    }

                    if (downloaded != metadata.totalBytes) {
                        throw new IOException("Connection ended at " + downloaded + " of "
                                + metadata.totalBytes + " bytes");
                    }

                    if (verifyCompletedDownload(part, finalApk, metadata)) return;
                    hashRetryCount++;
                    if (hashRetryCount >= 2) {
                        fail("Downloaded APK failed integrity verification twice. Check for updates again.");
                        return;
                    }
                    part.delete();
                } catch (IOException io) {
                    if (cancelRequested.get() || Thread.currentThread().isInterrupted()) return;
                    HttpURLConnection connection = activeConnection;
                    if (connection != null) connection.disconnect();
                    activeConnection = null;

                    long saved = part.isFile() ? part.length() : 0L;
                    publish(new AppUpdater.UpdateState(
                            AppUpdater.Phase.WAITING_NETWORK,
                            "Connection interrupted — " + formatBytes(saved)
                                    + " saved. Retrying automatically…",
                            saved, metadata.totalBytes, 0L,
                            metadata.releaseUpdatedAt, metadata.assetUrl,
                            metadata.expectedSha256, metadata.assetName,
                            metadata.candidateVersionName, metadata.candidateVersionCode), true);
                    sleepRetry();
                }
            }
        } catch (Throwable t) {
            if (!cancelRequested.get()) {
                fail(t.getMessage() == null || t.getMessage().trim().isEmpty()
                        ? t.getClass().getSimpleName() : t.getMessage());
            }
        }
    }

    private boolean verifyCompletedDownload(File part, File finalApk,
                                            AppUpdater.UpdateState metadata) throws IOException {
        publish(new AppUpdater.UpdateState(
                AppUpdater.Phase.VERIFYING,
                "Verifying downloaded Smart S Launcher APK…",
                metadata.totalBytes, metadata.totalBytes, 0L,
                metadata.releaseUpdatedAt, metadata.assetUrl,
                metadata.expectedSha256, metadata.assetName,
                metadata.candidateVersionName, metadata.candidateVersionCode), true);

        if (cancelRequested.get()) return true;
        String actualSha = AppUpdater.sha256Of(part);
        if (cancelRequested.get()) return true;
        if (!actualSha.equalsIgnoreCase(metadata.expectedSha256)) return false;

        if (finalApk.exists() && !finalApk.delete()) {
            fail("Could not replace an older downloaded update");
            return true;
        }
        if (!part.renameTo(finalApk)) {
            fail("Could not finalize the downloaded APK");
            return true;
        }

        UpdateInstaller.VerificationResult verification =
                UpdateInstaller.verifyDownloadedApk(this, finalApk);
        if (cancelRequested.get()) {
            finalApk.delete();
            return true;
        }
        if (!verification.valid) {
            finalApk.delete();
            fail(verification.message);
            return true;
        }

        completeReady(metadata, verification);
        return true;
    }

    private void completeReady(AppUpdater.UpdateState metadata,
                               UpdateInstaller.VerificationResult verification) {
        AppUpdater.UpdateState ready = metadata.withReady(
                verification.versionName, verification.versionCode);
        AppUpdater.updateFromService(this, ready, true);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(ready));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            //noinspection deprecation
            stopForeground(false);
        }
        stopSelf();
    }

    private HttpURLConnection openDownloadConnection(String rawUrl, long resumeFrom)
            throws IOException {
        HttpURLConnection connection = UpdateNetwork.open(
                this, rawUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "Smart-S-Launcher updater");
        connection.setRequestProperty("Accept",
                "application/vnd.android.package-archive, application/octet-stream");
        if (resumeFrom > 0L) connection.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
        connection.connect();
        return connection;
    }

    private void validateContentRange(String header, long expectedStart) throws IOException {
        if (header == null || !header.startsWith("bytes ")) {
            throw new IOException("Server did not confirm the resumed byte range");
        }
        String value = header.substring("bytes ".length());
        int dash = value.indexOf('-');
        if (dash <= 0) throw new IOException("Server returned an invalid Content-Range");
        long start;
        try {
            start = Long.parseLong(value.substring(0, dash));
        } catch (NumberFormatException e) {
            throw new IOException("Server returned an invalid Content-Range", e);
        }
        if (start != expectedStart) {
            throw new IOException("Server resumed from an unexpected byte offset");
        }
    }

    private void sleepRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void publish(AppUpdater.UpdateState state, boolean persist) {
        AppUpdater.updateFromService(this, state, persist);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(state));
    }

    private void cancelTransfer() {
        cancelRequested.set(true);
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        Future<?> currentWorker = worker;
        if (currentWorker != null) currentWorker.cancel(true);

        AppUpdater.clearDownloadedFiles(this);
        AppUpdater.UpdateState current = AppUpdater.current(this);
        AppUpdater.UpdateState canceled = current.withTransfer(
                AppUpdater.Phase.CANCELED, "Update download canceled.", 0L, 0L);
        AppUpdater.updateFromService(this, canceled, true);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(canceled));
        stopForegroundCompat();
        stopSelf();
    }

    private void pauseForSystem() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        Future<?> currentWorker = worker;
        if (currentWorker != null) currentWorker.cancel(true);

        AppUpdater.UpdateState current = AppUpdater.current(this);
        File part = new File(AppUpdater.updateDirectory(this), PART_FILE_NAME);
        AppUpdater.UpdateState paused = new AppUpdater.UpdateState(
                AppUpdater.Phase.PAUSED_SYSTEM,
                "Android paused the update. Reopen Smart S Launcher to resume.",
                part.isFile() ? part.length() : current.bytesDownloaded,
                current.totalBytes, 0L, current.releaseUpdatedAt, current.assetUrl,
                current.expectedSha256, current.assetName,
                current.candidateVersionName, current.candidateVersionCode);
        AppUpdater.updateFromService(this, paused, true);
        stopForegroundCompat();
        stopSelf();
    }

    private void fail(String message) {
        AppUpdater.UpdateState current = AppUpdater.current(this);
        AppUpdater.UpdateState failed = current.withTransfer(
                AppUpdater.Phase.ERROR, message, current.bytesDownloaded, 0L);
        AppUpdater.updateFromService(this, failed, true);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(failed));
        stopForegroundCompat();
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Smart S Launcher app updates", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Download and installation status for Smart S Launcher updates");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(AppUpdater.UpdateState state) {
        PendingIntent openApp = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                pendingFlags(PendingIntent.FLAG_UPDATE_CURRENT));

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(notificationTitle(state.phase))
                .setContentText(state.message.length() > 120
                        ? state.message.substring(0, 120) : state.message)
                .setContentIntent(openApp)
                .setOnlyAlertOnce(true)
                .setOngoing(state.active())
                .setCategory(Notification.CATEGORY_PROGRESS);

        if (state.phase == AppUpdater.Phase.DOWNLOADING
                || state.phase == AppUpdater.Phase.WAITING_NETWORK) {
            if (state.totalBytes > 0L) {
                int progress = (int) Math.max(0L, Math.min(100L,
                        (state.bytesDownloaded * 100L) / state.totalBytes));
                builder.setProgress(100, progress, false);
                builder.setSubText(progress + "% · " + formatBytes(state.bytesDownloaded)
                        + " / " + formatBytes(state.totalBytes));
            } else {
                builder.setProgress(0, 0, true);
            }
        } else if (state.phase == AppUpdater.Phase.VERIFYING) {
            builder.setProgress(0, 0, true);
        } else if (state.phase == AppUpdater.Phase.READY_TO_INSTALL) {
            builder.setProgress(100, 100, false);
            builder.setAutoCancel(true);
            PendingIntent install = PendingIntent.getBroadcast(
                    this, 2,
                    new Intent(this, UpdateInstallReceiver.class)
                            .setAction(UpdateInstallReceiver.ACTION_INSTALL),
                    pendingFlags(PendingIntent.FLAG_UPDATE_CURRENT));
            builder.addAction(0, "Install", install);
            builder.setContentIntent(install);
        }

        if (state.active()) {
            PendingIntent cancel = PendingIntent.getService(
                    this, 1,
                    new Intent(this, UpdateDownloadService.class).setAction(ACTION_CANCEL),
                    pendingFlags(PendingIntent.FLAG_UPDATE_CURRENT));
            builder.addAction(0, "Cancel", cancel);
        }
        return builder.build();
    }

    private int pendingFlags(int base) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? base | PendingIntent.FLAG_IMMUTABLE : base;
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            //noinspection deprecation
            stopForeground(false);
        }
    }

    private String notificationTitle(AppUpdater.Phase phase) {
        switch (phase) {
            case DOWNLOADING:
                return "Downloading Smart S Launcher update";
            case WAITING_NETWORK:
                return "Smart S Launcher update paused";
            case VERIFYING:
                return "Verifying Smart S Launcher update";
            case READY_TO_INSTALL:
                return "Smart S Launcher update ready";
            case ERROR:
                return "Smart S Launcher update needs attention";
            case CANCELED:
                return "Smart S Launcher update canceled";
            case PAUSED_SYSTEM:
                return "Smart S Launcher update paused by Android";
            default:
                return "Smart S Launcher app update";
        }
    }

    private String formatBytes(long bytes) {
        double value = Math.max(0L, bytes);
        if (value >= 1024d * 1024d * 1024d) {
            return String.format(Locale.ROOT, "%.2f GB", value / (1024d * 1024d * 1024d));
        }
        if (value >= 1024d * 1024d) {
            return String.format(Locale.ROOT, "%.1f MB", value / (1024d * 1024d));
        }
        if (value >= 1024d) {
            return String.format(Locale.ROOT, "%.1f KB", value / 1024d);
        }
        return Math.max(0L, bytes) + " B";
    }
}
