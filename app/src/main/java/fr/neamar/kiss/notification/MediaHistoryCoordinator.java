package fr.neamar.kiss.notification;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.utils.Log;

/**
 * Keeps active transport notifications represented by their individual persistent timeline ID.
 * Work is performed off the UI thread because album-art normalization may require bitmap I/O.
 */
public final class MediaHistoryCoordinator implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = MediaHistoryCoordinator.class.getSimpleName();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile boolean installed;

    public static void install(Application application) {
        if (installed) return;
        synchronized (MediaHistoryCoordinator.class) {
            if (installed) return;
            application.registerActivityLifecycleCallbacks(new MediaHistoryCoordinator());
            installed = true;
        }
    }

    public static void refresh(Context context, boolean addToHistory) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> refreshNow(app, addToHistory));
    }

    private static void refreshNow(Context context, boolean addToHistory) {
        NotificationListener listener = listenerInstance();
        if (listener == null) return;
        StatusBarNotification[] active;
        try {
            active = listener.getActiveNotifications();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to inspect active media notifications", e);
            return;
        }
        if (active == null || active.length == 0) return;

        boolean historyChanged = false;
        for (StatusBarNotification sbn : active) {
            if (!isMediaNotification(sbn)) continue;
            MediaNotificationSupport.capture(context, sbn);
            if (addToHistory) {
                KissApplication.getApplication(context).getDataHandler()
                        .addToHistory(NotificationListener.getTimelineId(sbn));
                historyChanged = true;
            }
        }
        if (historyChanged) context.sendBroadcast(new android.content.Intent(MainActivity.LOAD_OVER));
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
        if (activity instanceof MainActivity) refresh(activity, true);
    }

    @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
    @Override public void onActivityStarted(@NonNull Activity activity) { }
    @Override public void onActivityPaused(@NonNull Activity activity) { }
    @Override public void onActivityStopped(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
    @Override public void onActivityDestroyed(@NonNull Activity activity) { }
}
