package fr.neamar.kiss.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import fr.neamar.kiss.R;

/**
 * Small, isolated progress reporter for Smart S backup/restore.
 * It owns no backup data and can report either step-based or direct percentage progress.
 */
public final class BackupRestoreProgress {
    private static final String CHANNEL_ID = "smart_s_backup_restore";
    private static final int NOTIFICATION_ID = 0x53534252;

    private final Context context;
    private final NotificationManagerCompat manager;
    private final String operation;
    private final int totalSteps;
    private int completedSteps;
    private int lastPercent = -1;

    private BackupRestoreProgress(Context context, String operation, int totalSteps) {
        this.context = context.getApplicationContext();
        this.manager = NotificationManagerCompat.from(this.context);
        this.operation = operation;
        this.totalSteps = Math.max(1, totalSteps);
        createChannel();
        publish(0, operation + " 0%");
    }

    public static BackupRestoreProgress backup(Context context, int totalSteps) {
        return new BackupRestoreProgress(context, "Backing up Smart S", totalSteps);
    }

    public static BackupRestoreProgress restore(Context context, int totalSteps) {
        return new BackupRestoreProgress(context, "Restoring Smart S", totalSteps);
    }

    public void step() {
        completedSteps = Math.min(totalSteps, completedSteps + 1);
        setPercent(Math.round((completedSteps * 100f) / totalSteps));
    }

    public void setPercent(int percent) {
        int bounded = Math.max(0, Math.min(99, percent));
        if (bounded != lastPercent) publish(bounded, operation + " " + bounded + "%");
    }

    public void complete() {
        completedSteps = totalSteps;
        publishFinished(100, operation.startsWith("Backing") ? "Backup complete" : "Restore complete");
    }

    public void fail() {
        publishFinished(Math.max(0, lastPercent), operation.startsWith("Backing") ? "Backup failed" : "Restore failed");
    }

    private void publish(int percent, String text) {
        lastPercent = percent;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(operation)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, percent, false);
        notifySafely(builder);
    }

    private void publishFinished(int percent, String text) {
        lastPercent = percent;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(text)
                .setContentText(percent + "%")
                .setOnlyAlertOnce(true)
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, Math.max(0, Math.min(100, percent)), false);
        notifySafely(builder);
    }

    private void notifySafely(NotificationCompat.Builder builder) {
        try {
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) {
            Log.w("BackupRestoreProgress", "Notification permission is not available; progress notification cannot be shown");
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Backup and restore",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows Smart S backup and restore percentage progress");
        notificationManager.createNotificationChannel(channel);
    }
}
