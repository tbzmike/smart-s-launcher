package fr.neamar.kiss.notification;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.neamar.kiss.utils.Log;

/**
 * Persists visual content that Android exposes through a notification so history dialogs can keep
 * showing the same image after the originating notification has disappeared. Content URIs from
 * MessagingStyle are retained only as best-effort launch targets; access is re-validated on tap.
 */
public final class NotificationVisualSupport {
    private static final String TAG = NotificationVisualSupport.class.getSimpleName();
    private static final String PREFS = "notification-visual-history";
    private static final String DIR = "notification_visuals";
    private static final int MAX_EDGE = 768;
    private static final long MAX_AGE_MS = 45L * 24L * 60L * 60L * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public static final class Snapshot {
        public final Drawable image;
        public final String mediaUri;
        public final String mediaMime;

        Snapshot(Drawable image, String mediaUri, String mediaMime) {
            this.image = image;
            this.mediaUri = mediaUri;
            this.mediaMime = mediaMime;
        }

        public boolean hasImage() {
            return image != null;
        }

        public boolean hasPlayableVideo() {
            return !TextUtils.isEmpty(mediaUri) && mediaMime != null
                    && mediaMime.toLowerCase(Locale.ROOT).startsWith("video/");
        }
    }

    private NotificationVisualSupport() {}

    public static void captureAsync(Context context, String notificationId, StatusBarNotification sbn) {
        if (context == null || TextUtils.isEmpty(notificationId) || sbn == null) return;
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> captureNow(app, notificationId, sbn));
    }

    private static void captureNow(Context context, String notificationId, StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        MediaReference reference = findMessagingMedia(notification);
        Drawable visual = extractVisual(context, notification, reference);
        File file = visual == null ? null : visualFile(context, notificationId);
        boolean wroteImage = file != null && writeBitmap(file, drawableToBitmap(visual));

        android.content.SharedPreferences.Editor edit = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(notificationId + "|seen", System.currentTimeMillis());
        if (wroteImage) edit.putString(notificationId + "|image", file.getAbsolutePath());
        if (reference != null && reference.uri != null) {
            edit.putString(notificationId + "|media_uri", reference.uri.toString());
            edit.putString(notificationId + "|media_mime", reference.mime == null ? "" : reference.mime);
        }
        edit.apply();
        cleanup(context);
    }

    @Nullable
    public static Snapshot snapshot(Context context, String notificationId) {
        if (context == null || TextUtils.isEmpty(notificationId)) return null;
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String path = prefs.getString(notificationId + "|image", null);
        Drawable image = null;
        if (!TextUtils.isEmpty(path)) {
            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap != null) image = new BitmapDrawable(context.getResources(), bitmap);
        }
        String uri = prefs.getString(notificationId + "|media_uri", null);
        String mime = prefs.getString(notificationId + "|media_mime", null);
        if (image == null && TextUtils.isEmpty(uri)) return null;
        return new Snapshot(image, uri, mime);
    }

    public static boolean openMedia(Context context, Snapshot snapshot) {
        if (context == null || snapshot == null || TextUtils.isEmpty(snapshot.mediaUri)) return false;
        try {
            Uri uri = Uri.parse(snapshot.mediaUri);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (TextUtils.isEmpty(snapshot.mediaMime)) intent.setData(uri);
            else intent.setDataAndType(uri, snapshot.mediaMime);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (intent.resolveActivity(context.getPackageManager()) == null) return false;
            context.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Notification media URI is no longer playable", e);
            return false;
        }
    }

    @Nullable
    private static Drawable extractVisual(Context context, Notification notification,
                                          @Nullable MediaReference reference) {
        MediaController controller = mediaController(context, notification);
        if (controller != null) {
            try {
                MediaMetadata metadata = controller.getMetadata();
                if (metadata != null) {
                    Bitmap bitmap = firstBitmap(metadata, MediaMetadata.METADATA_KEY_ALBUM_ART,
                            MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                    if (bitmap != null) return new BitmapDrawable(context.getResources(), bitmap);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to read media artwork while persisting notification visual", e);
            }
        }

        Bundle extras = notification.extras;
        if (extras != null) {
            Drawable picture = drawableFromValue(context, extras.get(Notification.EXTRA_PICTURE));
            if (picture != null) return picture;
            Drawable large = drawableFromValue(context, extras.get(Notification.EXTRA_LARGE_ICON_BIG));
            if (large != null) return large;
            large = drawableFromValue(context, extras.get(Notification.EXTRA_LARGE_ICON));
            if (large != null) return large;
        }

        if (reference != null && reference.uri != null && reference.mime != null
                && reference.mime.toLowerCase(Locale.ROOT).startsWith("image/")) {
            Drawable fromUri = drawableFromUri(context, reference.uri);
            if (fromUri != null) return fromUri;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notification.getLargeIcon() != null) {
            try {
                return notification.getLargeIcon().loadDrawable(context);
            } catch (RuntimeException ignored) {
                // No visual is preferable to crashing notification capture.
            }
        }
        return null;
    }

    @Nullable
    private static MediaReference findMessagingMedia(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        Parcelable[] bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (bundles == null) return null;
        List<Notification.MessagingStyle.Message> messages =
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles);
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Notification.MessagingStyle.Message message = messages.get(i);
            if (message == null || message.getDataUri() == null) continue;
            return new MediaReference(message.getDataUri(), message.getDataMimeType());
        }
        return null;
    }

    @Nullable
    private static MediaController mediaController(Context context, Notification notification) {
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
            try {
                return ((Icon) value).loadDrawable(context);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return value instanceof Drawable ? (Drawable) value : null;
    }

    @Nullable
    private static Drawable drawableFromUri(Context context, Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
        } catch (IOException | SecurityException | RuntimeException e) {
            Log.w(TAG, "Unable to copy notification image URI", e);
            return null;
        }
    }

    private static File visualFile(Context context, String notificationId) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "Unable to create notification visual directory");
        return new File(dir, digest(notificationId) + ".jpg");
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null) {
            return scale(((BitmapDrawable) drawable).getBitmap());
        }
        int width = Math.max(1, drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : MAX_EDGE);
        int height = Math.max(1, drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : MAX_EDGE);
        float scale = Math.min(1f, MAX_EDGE / (float) Math.max(width, height));
        width = Math.max(1, Math.round(width * scale));
        height = Math.max(1, Math.round(height * scale));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }

    private static Bitmap scale(Bitmap source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= MAX_EDGE) return source;
        float factor = MAX_EDGE / (float) longest;
        return Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(source.getWidth() * factor)),
                Math.max(1, Math.round(source.getHeight() * factor)), true);
    }

    private static boolean writeBitmap(File file, Bitmap bitmap) {
        if (bitmap == null) return false;
        try (FileOutputStream out = new FileOutputStream(file)) {
            return bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unable to persist notification visual", e);
            return false;
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static void cleanup(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        for (File file : files) {
            if (file != null && file.lastModified() < cutoff && !file.delete()) {
                Log.w(TAG, "Unable to delete expired notification visual " + file.getName());
            }
        }
    }

    private static final class MediaReference {
        final Uri uri;
        final String mime;

        MediaReference(Uri uri, String mime) {
            this.uri = uri;
            this.mime = mime;
        }
    }
}
