package fr.neamar.kiss.notification;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.ShortcutUtil;

/**
 * Captures the identity image Android exposes for a notification without scraping another app's
 * private data. Identity artwork is intentionally separate from NotificationVisualSupport so a
 * sender/group avatar never replaces an attached image, video thumbnail, or media artwork.
 */
public final class NotificationAvatarSupport {
    private static final String TAG = NotificationAvatarSupport.class.getSimpleName();
    private static final String PREFS = "notification-avatar-history";
    private static final String DIR = "notification_avatars";
    private static final int MAX_EDGE = 256;
    private static final long MAX_AGE_MS = 45L * 24L * 60L * 60L * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private NotificationAvatarSupport() { }

    /** Capture the best Android-exposed identity image and persist it for saved history. */
    public static void captureAsync(@NonNull Context context, @NonNull String notificationId,
                                    @NonNull StatusBarNotification sbn) {
        if (TextUtils.isEmpty(notificationId)) return;
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            Drawable avatar = extractAvatar(app, sbn);
            if (avatar == null) return;
            File file = avatarFile(app, notificationId);
            if (!writeBitmap(file, drawableToBitmap(avatar))) return;
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(notificationId + "|image", file.getAbsolutePath())
                    .putLong(notificationId + "|seen", System.currentTimeMillis())
                    .apply();
            cleanup(app);
            // The initial launcher render can race the background disk copy. Reload only after a
            // real avatar was captured so live timeline/history rows can replace their app-icon
            // fallback without altering notification launch or history ordering behavior.
            app.sendBroadcast(MainActivity.internalBroadcast(app, MainActivity.LOAD_OVER));
        });
    }

    /** Return only a captured identity avatar; callers keep their existing app-icon fallback. */
    @Nullable
    public static Drawable avatar(@Nullable Context context, @Nullable String notificationId) {
        if (context == null || TextUtils.isEmpty(notificationId)) return null;
        String path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(notificationId + "|image", null);
        if (TextUtils.isEmpty(path)) return null;
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
    }

    @Nullable
    private static Drawable extractAvatar(@NonNull Context context,
                                          @NonNull StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) return null;

        // 1. MessagingStyle sender/person avatar. This is the most specific identity image.
        Drawable personAvatar = personAvatar(context, notification);
        if (personAvatar != null) return personAvatar;

        // 2. A notification large icon is commonly the chat/group/channel avatar in social apps.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notification.getLargeIcon() != null) {
            Drawable large = loadIcon(context, notification.getLargeIcon());
            if (large != null) return large;
        }

        Bundle extras = notification.extras;
        if (extras != null) {
            Drawable large = drawableFromValue(context, extras.get(Notification.EXTRA_LARGE_ICON_BIG));
            if (large != null) return large;
            large = drawableFromValue(context, extras.get(Notification.EXTRA_LARGE_ICON));
            if (large != null) return large;
        }

        // 3. Conversation shortcut icon. This is durable app-published identity metadata and is
        // also the same public Android shortcut mechanism used by exact saved-notification routing.
        return shortcutAvatar(context, sbn, notification);
    }

    @Nullable
    private static Drawable personAvatar(@NonNull Context context,
                                         @NonNull Notification notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
        Bundle extras = notification.extras;
        if (extras == null) return null;

        // Android's public decoder for MessagingStyle message bundles was added in API 30.
        // Keep this block independently guarded so Smart S remains installable down to minSdk 21.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Parcelable[] bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (bundles != null) {
                List<Notification.MessagingStyle.Message> messages =
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles);
                if (messages != null) {
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        Notification.MessagingStyle.Message message = messages.get(i);
                        if (message == null) continue;
                        Person sender = message.getSenderPerson();
                        Drawable avatar = drawableFromPerson(context, sender);
                        if (avatar != null) return avatar;
                    }
                }
            }
        }

        Parcelable messagingPerson = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
        if (messagingPerson instanceof Person) {
            Drawable avatar = drawableFromPerson(context, (Person) messagingPerson);
            if (avatar != null) return avatar;
        }

        ArrayList<Parcelable> people = extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST);
        if (people != null) {
            for (int i = people.size() - 1; i >= 0; i--) {
                Parcelable value = people.get(i);
                if (!(value instanceof Person)) continue;
                Drawable avatar = drawableFromPerson(context, (Person) value);
                if (avatar != null) return avatar;
            }
        }
        return null;
    }

    @Nullable
    private static Drawable drawableFromPerson(@NonNull Context context, @Nullable Person person) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || person == null) return null;
        return loadIcon(context, person.getIcon());
    }

    @Nullable
    private static Drawable shortcutAvatar(@NonNull Context context,
                                           @NonNull StatusBarNotification sbn,
                                           @NonNull Notification notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || TextUtils.isEmpty(notification.getShortcutId())) return null;
        LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps == null || !launcherApps.hasShortcutHostPermission()) return null;
        try {
            ShortcutInfo shortcut = ShortcutUtil.getShortCut(context, sbn.getUser(),
                    sbn.getPackageName(), notification.getShortcutId());
            if (shortcut == null) return null;
            return launcherApps.getShortcutIconDrawable(shortcut,
                    context.getResources().getDisplayMetrics().densityDpi);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to resolve conversation shortcut avatar", e);
            return null;
        }
    }

    @Nullable
    private static Drawable drawableFromValue(@NonNull Context context, @Nullable Object value) {
        if (value instanceof Bitmap) {
            return new BitmapDrawable(context.getResources(), (Bitmap) value);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && value instanceof Icon) {
            return loadIcon(context, (Icon) value);
        }
        return value instanceof Drawable ? (Drawable) value : null;
    }

    @Nullable
    private static Drawable loadIcon(@NonNull Context context, @Nullable Icon icon) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || icon == null) return null;
        try {
            return icon.loadDrawable(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to load notification identity icon", e);
            return null;
        }
    }

    private static File avatarFile(@NonNull Context context, @NonNull String notificationId) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Unable to create notification avatar directory");
        }
        return new File(dir, digest(notificationId) + ".png");
    }

    @Nullable
    private static Bitmap drawableToBitmap(@Nullable Drawable drawable) {
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null) {
            return scale(((BitmapDrawable) drawable).getBitmap());
        }
        int width = Math.max(1, drawable.getIntrinsicWidth() > 0
                ? drawable.getIntrinsicWidth() : MAX_EDGE);
        int height = Math.max(1, drawable.getIntrinsicHeight() > 0
                ? drawable.getIntrinsicHeight() : MAX_EDGE);
        float factor = Math.min(1f, MAX_EDGE / (float) Math.max(width, height));
        width = Math.max(1, Math.round(width * factor));
        height = Math.max(1, Math.round(height * factor));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }

    private static Bitmap scale(@NonNull Bitmap source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= MAX_EDGE) return source;
        float factor = MAX_EDGE / (float) longest;
        return Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(source.getWidth() * factor)),
                Math.max(1, Math.round(source.getHeight() * factor)), true);
    }

    private static boolean writeBitmap(@NonNull File file, @Nullable Bitmap bitmap) {
        if (bitmap == null) return false;
        try (FileOutputStream out = new FileOutputStream(file)) {
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unable to persist notification avatar", e);
            return false;
        }
    }

    private static String digest(@NonNull String value) {
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

    private static void cleanup(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        for (File file : files) {
            if (file != null && file.lastModified() < cutoff && !file.delete()) {
                Log.w(TAG, "Unable to delete expired notification avatar " + file.getName());
            }
        }
    }
}
