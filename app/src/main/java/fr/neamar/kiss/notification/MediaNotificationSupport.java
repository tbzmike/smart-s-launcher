package fr.neamar.kiss.notification;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.LruCache;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import fr.neamar.kiss.utils.LauncherScrollWorkGate;
import fr.neamar.kiss.utils.Log;

/**
 * Shared media-notification bridge for launcher history.
 *
 * Artwork is owned by one exact Android notification post, never by a package. This matters for
 * apps such as WhatsApp and MarkVault that can publish calls, ordinary messages and media playback
 * from the same package. A call/profile picture or one video's thumbnail must never become the
 * package's default artwork for unrelated history rows.
 */
public final class MediaNotificationSupport {
    private static final String TAG = MediaNotificationSupport.class.getSimpleName();
    private static final String PREFS = "media-notification-history";
    private static final String DIR = "media_notification_art";
    private static final int MAX_ART_EDGE = 512;
    private static final long MAX_ART_AGE_MS = 45L * 24L * 60L * 60L * 1000L;
    private static final int MEMORY_CACHE_BYTES = 8 * 1024 * 1024;
    private static final LruCache<String, Bitmap> ART_CACHE =
            new LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getAllocationByteCount();
                }
            };

    public static final class Snapshot {
        public final String packageName;
        public final String notificationId;
        public final long postTime;
        public final Drawable artwork;
        public final boolean active;
        public final boolean playing;
        public final boolean previous;
        public final boolean playPause;
        public final boolean next;

        Snapshot(String packageName, String notificationId, long postTime, Drawable artwork,
                 boolean active, boolean playing, boolean previous, boolean playPause, boolean next) {
            this.packageName = packageName;
            this.notificationId = notificationId;
            this.postTime = postTime;
            this.artwork = artwork;
            this.active = active;
            this.playing = playing;
            this.previous = previous;
            this.playPause = playPause;
            this.next = next;
        }
    }

    private MediaNotificationSupport() {}

    /**
     * Capture artwork for exactly this notification post. This is called from the media-history
     * background worker, never from row binding.
     */
    public static void capture(Context context, StatusBarNotification sbn) {
        if (context == null || sbn == null || sbn.getNotification() == null) return;
        Notification notification = sbn.getNotification();
        if (!isTransportMediaNotification(notification)) return;

        String packageName = sbn.getPackageName();
        String notificationId = NotificationListener.getTimelineId(sbn);
        long postTime = sbn.getPostTime();
        String eventKey = MediaEventIdentity.create(packageName, notificationId, postTime);
        if (eventKey.isEmpty()) return;

        Drawable artwork = extractArtwork(context, notification);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit()
                .putString(eventKey + "|package", packageName)
                .putString(eventKey + "|notification", notificationId)
                .putLong(eventKey + "|post", postTime)
                .putLong(eventKey + "|seen", System.currentTimeMillis());

        if (artwork != null) {
            Bitmap bitmap = drawableToBitmap(artwork);
            File file = artworkFile(context, eventKey);
            if (writeArtwork(file, bitmap)) {
                edit.putString(eventKey + "|art", file.getAbsolutePath());
                if (bitmap != null) ART_CACHE.put(eventKey, bitmap);
            }
        }
        edit.apply();
        cleanupOldArtwork(context);
    }

    /**
     * Resolve only artwork/control state belonging to this exact saved event.
     *
     * There is deliberately no package-level fallback. If this row is a text message, call or
     * ordinary app history item, media artwork from another event in the same app cannot appear.
     */
    @Nullable
    public static Snapshot snapshotForEvent(Context context, String packageName,
                                            String notificationId, long postTime) {
        if (context == null || TextUtils.isEmpty(packageName)
                || TextUtils.isEmpty(notificationId) || postTime <= 0L) return null;
        String eventKey = MediaEventIdentity.create(packageName, notificationId, postTime);
        if (eventKey.isEmpty()) return null;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String storedPackage = prefs.getString(eventKey + "|package", null);
        String storedNotification = prefs.getString(eventKey + "|notification", null);
        long storedPost = prefs.getLong(eventKey + "|post", 0L);
        boolean capturedExactEvent = packageName.equals(storedPackage)
                && notificationId.equals(storedNotification) && postTime == storedPost;

        Drawable persisted = capturedExactEvent
                ? loadPersistedArtwork(context, eventKey, prefs.getString(eventKey + "|art", null))
                : null;

        // Never scan active notifications or interrogate a MediaSession during a fling.
        if (LauncherScrollWorkGate.isScrolling()) {
            return persisted == null ? null : new Snapshot(packageName, notificationId, postTime,
                    persisted, false, false, false, false, false);
        }

        StatusBarNotification active =
                findActiveMediaNotification(packageName, notificationId, postTime);
        if (active == null || active.getNotification() == null) {
            return persisted == null ? null : new Snapshot(packageName, notificationId, postTime,
                    persisted, false, false, false, false, false);
        }

        ControlState controls = readControlState(context, active.getNotification());
        return new Snapshot(packageName, notificationId, postTime, persisted, true,
                controls.playing, controls.previous, controls.playPause, controls.next);
    }

    /** Execute a media action only against the exact notification/session represented by the row. */
    public static boolean perform(Context context, String packageName, String notificationId,
                                  long postTime, MediaControlClassifier.Kind kind) {
        if (context == null || TextUtils.isEmpty(packageName) || TextUtils.isEmpty(notificationId)
                || postTime <= 0L || kind == null || LauncherScrollWorkGate.isScrolling()) {
            return false;
        }
        StatusBarNotification sbn =
                findActiveMediaNotification(packageName, notificationId, postTime);
        if (sbn == null || sbn.getNotification() == null) return false;
        Notification notification = sbn.getNotification();

        MediaController controller = controller(context, notification);
        if (controller != null) {
            try {
                MediaController.TransportControls controls = controller.getTransportControls();
                if (kind == MediaControlClassifier.Kind.PREVIOUS) controls.skipToPrevious();
                else if (kind == MediaControlClassifier.Kind.NEXT) controls.skipToNext();
                else if (kind == MediaControlClassifier.Kind.PLAY_PAUSE) {
                    PlaybackState state = controller.getPlaybackState();
                    if (state != null && isPlayingState(state.getState())) controls.pause();
                    else controls.play();
                } else return false;
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "MediaSession control failed; falling back to notification action", e);
            }
        }

        Notification.Action[] actions = notification.actions;
        if (actions == null) return false;
        for (Notification.Action action : actions) {
            if (action == null || action.actionIntent == null) continue;
            if (MediaControlClassifier.classify(action.title) != kind) continue;
            try {
                action.actionIntent.send();
                return true;
            } catch (PendingIntent.CanceledException | RuntimeException e) {
                Log.w(TAG, "Media notification action failed", e);
                return false;
            }
        }
        return false;
    }

    /**
     * Strict media classification shared by capture and history seeding.
     *
     * Calls are explicitly excluded. A notification is media when Android marks it transport,
     * exposes a MediaSession, or exposes an actual playback action. "Call back" is not a playback
     * action (MediaControlClassifier deliberately rejects it).
     */
    public static boolean isTransportMediaNotification(Notification notification) {
        if (notification == null || isCallNotification(notification)) return false;
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

    private static boolean isCallNotification(Notification notification) {
        if (Notification.CATEGORY_CALL.equals(notification.category)) return true;
        Bundle extras = notification.extras;
        if (extras == null) return false;
        String template = extras.getString(Notification.EXTRA_TEMPLATE);
        return template != null && template.contains("CallStyle");
    }

    private static final class ControlState {
        boolean previous;
        boolean playPause;
        boolean next;
        boolean playing;
    }

    private static ControlState readControlState(Context context, Notification notification) {
        ControlState result = new ControlState();
        Notification.Action[] actions = notification.actions;
        if (actions != null) {
            for (Notification.Action action : actions) {
                MediaControlClassifier.Kind kind = action == null
                        ? MediaControlClassifier.Kind.OTHER
                        : MediaControlClassifier.classify(action.title);
                if (kind == MediaControlClassifier.Kind.PREVIOUS) result.previous = true;
                else if (kind == MediaControlClassifier.Kind.PLAY_PAUSE) result.playPause = true;
                else if (kind == MediaControlClassifier.Kind.NEXT) result.next = true;
            }
        }

        MediaController controller = controller(context, notification);
        if (controller != null) {
            try {
                PlaybackState state = controller.getPlaybackState();
                if (state != null) {
                    long actionsMask = state.getActions();
                    result.previous |= (actionsMask & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0;
                    result.next |= (actionsMask & PlaybackState.ACTION_SKIP_TO_NEXT) != 0;
                    result.playPause |= (actionsMask & (PlaybackState.ACTION_PLAY
                            | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)) != 0;
                    result.playing = isPlayingState(state.getState());
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to read MediaSession playback state", e);
            }
        }
        return result;
    }

    private static boolean isPlayingState(int state) {
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING;
    }

    @Nullable
    private static MediaController controller(Context context, Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return null;
        Object token = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        if (!(token instanceof MediaSession.Token)) return null;
        try {
            return new MediaController(context, (MediaSession.Token) token);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static Drawable extractArtwork(Context context, Notification notification) {
        Bundle extras = notification.extras;
        MediaController controller = controller(context, notification);
        if (controller != null) {
            try {
                MediaMetadata metadata = controller.getMetadata();
                if (metadata != null) {
                    Bitmap bitmap = firstBitmap(metadata, MediaMetadata.METADATA_KEY_ALBUM_ART,
                            MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                    if (bitmap != null) return new BitmapDrawable(context.getResources(), bitmap);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to read MediaSession artwork", e);
            }
        }
        if (extras != null) {
            Drawable d = drawableFromValue(context, extras.get(Notification.EXTRA_LARGE_ICON_BIG));
            if (d != null) return d;
            d = drawableFromValue(context, extras.get(Notification.EXTRA_LARGE_ICON));
            if (d != null) return d;
            d = drawableFromValue(context, extras.get(Notification.EXTRA_PICTURE));
            if (d != null) return d;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notification.getLargeIcon() != null) {
            try { return notification.getLargeIcon().loadDrawable(context); }
            catch (RuntimeException ignored) { }
        }
        return null;
    }

    @Nullable
    private static Bitmap firstBitmap(MediaMetadata metadata, String... keys) {
        for (String key : keys) {
            Bitmap bitmap = metadata.getBitmap(key);
            if (bitmap != null) return bitmap;
        }
        return null;
    }

    @Nullable
    private static Drawable drawableFromValue(Context context, Object value) {
        if (value instanceof Bitmap) return new BitmapDrawable(context.getResources(), (Bitmap) value);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && value instanceof Icon) {
            try { return ((Icon) value).loadDrawable(context); }
            catch (RuntimeException ignored) { }
        }
        return value instanceof Drawable ? (Drawable) value : null;
    }

    @Nullable
    private static StatusBarNotification findActiveMediaNotification(
            String packageName, String notificationId, long postTime) {
        NotificationListener listener = getListener();
        if (listener == null) return null;
        StatusBarNotification[] active;
        try { active = listener.getActiveNotifications(); }
        catch (RuntimeException e) { return null; }
        if (active == null) return null;

        for (StatusBarNotification sbn : active) {
            if (sbn == null || sbn.getNotification() == null
                    || !packageName.equals(sbn.getPackageName())
                    || postTime != sbn.getPostTime()
                    || !notificationId.equals(NotificationListener.getTimelineId(sbn))) {
                continue;
            }
            return isTransportMediaNotification(sbn.getNotification()) ? sbn : null;
        }
        return null;
    }

    @Nullable
    private static NotificationListener getListener() {
        try {
            Field field = NotificationListener.class.getDeclaredField("instance");
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof NotificationListener ? (NotificationListener) value : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static File artworkFile(Context context, String eventKey) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, digest(eventKey) + ".png");
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null) {
            return scale(((BitmapDrawable) drawable).getBitmap());
        }
        int width = Math.max(1, Math.min(MAX_ART_EDGE,
                drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : MAX_ART_EDGE));
        int height = Math.max(1, Math.min(MAX_ART_EDGE,
                drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : MAX_ART_EDGE));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }

    private static Bitmap scale(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (longest <= MAX_ART_EDGE) return source;
        float factor = MAX_ART_EDGE / (float) longest;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(width * factor)),
                Math.max(1, Math.round(height * factor)), true);
    }

    private static boolean writeArtwork(File file, Bitmap bitmap) {
        if (bitmap == null) return false;
        try (FileOutputStream out = new FileOutputStream(file)) {
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unable to persist media notification artwork", e);
            return false;
        }
    }

    @Nullable
    private static Drawable loadPersistedArtwork(Context context, String eventKey, String path) {
        if (TextUtils.isEmpty(path)) return null;
        Bitmap bitmap = ART_CACHE.get(eventKey);
        if (bitmap == null) {
            // Disk decode is optional decoration and must never compete with an active fling.
            if (LauncherScrollWorkGate.isScrolling()) return null;
            bitmap = android.graphics.BitmapFactory.decodeFile(path);
            if (bitmap != null) ART_CACHE.put(eventKey, bitmap);
        }
        return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
    }

    private static void cleanupOldArtwork(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MAX_ART_AGE_MS;
        for (File file : files) {
            if (file != null && file.lastModified() < cutoff) file.delete();
        }
    }

    public static void trimMemory(boolean severe) {
        if (severe) ART_CACHE.evictAll();
        else ART_CACHE.trimToSize(MEMORY_CACHE_BYTES / 2);
    }
}
