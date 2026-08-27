package fr.neamar.kiss.notification;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import fr.neamar.kiss.utils.Log;

/**
 * Shared media-notification bridge for launcher history. It stores only artwork already exposed
 * through NotificationListener access; no music-app private storage is read.
 */
public final class MediaNotificationSupport {
    private static final String TAG = MediaNotificationSupport.class.getSimpleName();
    private static final String PREFS = "media-notification-history";
    private static final String DIR = "media_notification_art";
    private static final int MAX_ART_EDGE = 512;
    private static final long MAX_ART_AGE_MS = 45L * 24L * 60L * 60L * 1000L;

    public static final class Snapshot {
        public final String packageName;
        public final Drawable artwork;
        public final boolean active;
        public final boolean playing;
        public final boolean previous;
        public final boolean playPause;
        public final boolean next;

        Snapshot(String packageName, Drawable artwork, boolean active, boolean playing,
                 boolean previous, boolean playPause, boolean next) {
            this.packageName = packageName;
            this.artwork = artwork;
            this.active = active;
            this.playing = playing;
            this.previous = previous;
            this.playPause = playPause;
            this.next = next;
        }
    }

    private MediaNotificationSupport() {}

    public static void capture(Context context, StatusBarNotification sbn) {
        if (context == null || sbn == null || sbn.getNotification() == null) return;
        Notification notification = sbn.getNotification();
        if (!isMediaNotification(notification)) return;

        Drawable artwork = extractArtwork(context, notification);
        String packageName = sbn.getPackageName();
        String appLabel = appLabel(context, packageName);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit()
                .putString("label:" + normalizeLabel(appLabel), packageName)
                .putString(packageName + "|label", appLabel)
                .putLong(packageName + "|seen", System.currentTimeMillis());

        if (artwork != null) {
            File file = artworkFile(context, packageName);
            if (writeArtwork(file, drawableToBitmap(artwork))) {
                edit.putString(packageName + "|art", file.getAbsolutePath());
            }
        }
        edit.apply();
        cleanupOldArtwork(context, prefs);
    }

    @Nullable
    public static Snapshot snapshotForLabel(Context context, CharSequence appLabel) {
        if (context == null || appLabel == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String packageName = prefs.getString("label:" + normalizeLabel(appLabel.toString()), null);
        if (TextUtils.isEmpty(packageName)) return null;
        return snapshotForPackage(context, packageName);
    }

    @Nullable
    public static Snapshot snapshotForPackage(Context context, String packageName) {
        if (context == null || TextUtils.isEmpty(packageName)) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Drawable persisted = loadPersistedArtwork(context, prefs.getString(packageName + "|art", null));

        StatusBarNotification active = findActiveMediaNotification(packageName);
        if (active == null || active.getNotification() == null) {
            return persisted == null ? null : new Snapshot(packageName, persisted, false, false,
                    false, false, false);
        }

        Notification notification = active.getNotification();
        Drawable liveArt = extractArtwork(context, notification);
        Drawable artwork = liveArt != null ? liveArt : persisted;
        ControlState controls = readControlState(context, notification);
        return new Snapshot(packageName, artwork, true, controls.playing,
                controls.previous, controls.playPause, controls.next);
    }

    public static boolean perform(Context context, String packageName, MediaControlClassifier.Kind kind) {
        if (context == null || TextUtils.isEmpty(packageName) || kind == null) return false;
        StatusBarNotification sbn = findActiveMediaNotification(packageName);
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

    private static boolean isMediaNotification(Notification notification) {
        if (notification == null) return false;
        if (Notification.CATEGORY_TRANSPORT.equals(notification.category)) return true;
        Bundle extras = notification.extras;
        if (extras != null && extras.get(Notification.EXTRA_MEDIA_SESSION) != null) return true;
        Notification.Action[] actions = notification.actions;
        if (actions != null) {
            for (Notification.Action action : actions) {
                if (action != null && MediaControlClassifier.classify(action.title)
                        != MediaControlClassifier.Kind.OTHER) return true;
            }
        }
        return false;
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
    private static StatusBarNotification findActiveMediaNotification(String packageName) {
        NotificationListener listener = getListener();
        if (listener == null) return null;
        StatusBarNotification[] active;
        try { active = listener.getActiveNotifications(); }
        catch (RuntimeException e) { return null; }
        if (active == null) return null;
        StatusBarNotification best = null;
        for (StatusBarNotification sbn : active) {
            if (sbn == null || !packageName.equals(sbn.getPackageName()) || sbn.getNotification() == null) continue;
            if (!isMediaNotification(sbn.getNotification())) continue;
            if (best == null || sbn.getPostTime() > best.getPostTime()) best = sbn;
        }
        return best;
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

    private static String appLabel(Context context, String packageName) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence label = info.loadLabel(context.getPackageManager());
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private static String normalizeLabel(String label) {
        return label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
    }

    private static File artworkFile(Context context, String packageName) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, digest(packageName) + ".png");
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        int width = Math.max(1, Math.min(MAX_ART_EDGE, drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : MAX_ART_EDGE));
        int height = Math.max(1, Math.min(MAX_ART_EDGE, drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : MAX_ART_EDGE));
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
    private static Drawable loadPersistedArtwork(Context context, String path) {
        if (TextUtils.isEmpty(path)) return null;
        Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(path);
        return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
    }

    private static void cleanupOldArtwork(Context context, SharedPreferences prefs) {
        File dir = new File(context.getFilesDir(), DIR);
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MAX_ART_AGE_MS;
        for (File file : files) {
            if (file != null && file.lastModified() < cutoff) file.delete();
        }
    }
}
