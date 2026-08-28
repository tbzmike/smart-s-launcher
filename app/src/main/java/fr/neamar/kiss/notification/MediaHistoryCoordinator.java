package fr.neamar.kiss.notification;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.utils.Log;

/**
 * Keeps notification visuals captured for history and active transport notifications represented
 * by their individual persistent timeline ID. Bitmap work stays off the launcher UI thread.
 */
public final class MediaHistoryCoordinator implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = MediaHistoryCoordinator.class.getSimpleName();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean REFRESH_RUNNING = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, Long> CAPTURE_FINGERPRINTS =
            new ConcurrentHashMap<>();
    private static final Set<String> SEEDED_MEDIA_HISTORY = ConcurrentHashMap.newKeySet();
    private static final long RESUME_REFRESH_MIN_INTERVAL_MS = 5000L;
    private static volatile boolean installed;
    private static volatile long lastResumeRefreshElapsed;

    public static void install(Application application) {
        if (installed) return;
        synchronized (MediaHistoryCoordinator.class) {
            if (installed) return;
            application.registerActivityLifecycleCallbacks(new MediaHistoryCoordinator());
            installed = true;
        }
    }

    public static void refresh(Context context, boolean addToHistory) {
        if (context == null || !REFRESH_RUNNING.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                refreshNow(app, addToHistory);
            } finally {
                REFRESH_RUNNING.set(false);
            }
        });
    }

    private static void refreshNow(Context context, boolean addToHistory) {
        NotificationListener listener = listenerInstance();
        if (listener == null) return;
        StatusBarNotification[] active;
        try {
            active = listener.getActiveNotifications();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to inspect active notifications", e);
            return;
        }
        if (active == null) return;

        boolean historyChanged = false;
        Set<String> activeIds = new HashSet<>(Math.max(4, active.length * 2));
        for (StatusBarNotification sbn : active) {
            if (sbn == null || sbn.getNotification() == null) continue;

            String timelineId = NotificationListener.getTimelineId(sbn);
            activeIds.add(timelineId);
            long fingerprint = captureFingerprint(sbn);
            Long previousFingerprint = CAPTURE_FINGERPRINTS.put(timelineId, fingerprint);
            boolean contentChanged = previousFingerprint == null
                    || previousFingerprint.longValue() != fingerprint;

            // Capturing notification visuals can decode/scale bitmaps and write JPEG files. Do that
            // only for a new or actually changed notification instead of on every Home resume.
            if (contentChanged) {
                NotificationVisualSupport.captureAsync(context, timelineId, sbn);
            }

            if (!isMediaNotification(sbn)) continue;
            if (contentChanged) {
                MediaNotificationSupport.capture(context, sbn);
            }
            if (addToHistory && SEEDED_MEDIA_HISTORY.add(timelineId)) {
                KissApplication.getApplication(context).getDataHandler().addToHistory(timelineId);
                historyChanged = true;
            }
        }

        // Forget ended notifications so a future notification reusing the same timeline id can be
        // captured and seeded normally. This also keeps the process-local maps bounded.
        CAPTURE_FINGERPRINTS.keySet().removeIf(id -> !activeIds.contains(id));
        SEEDED_MEDIA_HISTORY.removeIf(id -> !activeIds.contains(id));

        if (historyChanged) {
            context.sendBroadcast(MainActivity.internalBroadcast(context, MainActivity.LOAD_OVER));
        }
    }

    private static long captureFingerprint(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        long value = 17L;
        value = 31L * value + sbn.getPostTime();
        value = 31L * value + notification.when;
        Bundle extras = notification.extras;
        if (extras != null) {
            value = 31L * value + textHash(extras.getCharSequence(Notification.EXTRA_TITLE));
            value = 31L * value + textHash(extras.getCharSequence(Notification.EXTRA_TEXT));
            value = 31L * value + textHash(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        }
        return value;
    }

    private static int textHash(CharSequence value) {
        return value == null ? 0 : value.toString().hashCode();
    }

    private static boolean isMediaNotification(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return false;
        Notification notification = sbn.getNotification();
        if (Notification.CATEGORY_TRANSPORT.equals(notification.category)) return true;
        Bundle extras = notification.extras;
        if (extras != null && extras.get(Notification.EXTRA_MEDIA_SESSION) != null) return true;
        Notification.Action[] actions = notification.actions;
        if (actions == null) return false;
        for (Notification.Action action : actions) {
            if (action != null && MediaControlClassifier.classify(action.title)
                    != MediaControlClassifier.Kind.OTHER) return true;
        }
        return false;
    }

    @Nullable
    private static NotificationListener listenerInstance() {
        try {
            Field field = NotificationListener.class.getDeclaredField("instance");
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof NotificationListener ? (NotificationListener) value : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override public void onActivityResumed(@NonNull Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastResumeRefreshElapsed < RESUME_REFRESH_MIN_INTERVAL_MS) return;
        lastResumeRefreshElapsed = now;
        refresh(activity, true);
    }

    @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
    @Override public void onActivityStarted(@NonNull Activity activity) { }
    @Override public void onActivityPaused(@NonNull Activity activity) { }
    @Override public void onActivityStopped(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
    @Override public void onActivityDestroyed(@NonNull Activity activity) { }
}
