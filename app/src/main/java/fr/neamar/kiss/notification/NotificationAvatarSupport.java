package fr.neamar.kiss.notification;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.content.SharedPreferences;
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
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.LruCache;

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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.ShortcutUtil;

/**
 * Captures identity artwork Android publicly exposes for notifications and keeps a durable local
 * cache. Sender/group/channel artwork is deliberately separate from attached media artwork.
 */
public final class NotificationAvatarSupport {
    private static final String TAG = NotificationAvatarSupport.class.getSimpleName();
    private static final String PREFS = "notification-avatar-history";
    private static final String DIR = "notification_avatars";
    private static final int MAX_EDGE = 256;
    public static final int HISTORY_SCAN_LIMIT = 200;
    private static final long MAX_AGE_MS = 45L * 24L * 60L * 60L * 1000L;
    private static final int WORK_QUEUE_CAPACITY = 8;
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY),
            runnable -> new Thread(runnable, "smart-s-notification-avatar"),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final int MEMORY_CACHE_BYTES = 8 * 1024 * 1024;
    private static final LruCache<String, Bitmap> MEMORY_CACHE =
            new LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getAllocationByteCount();
                }
            };

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    public interface LoadCallback {
        void onFinished(int scanned, int linked, int freshlyResolved);
    }

    private NotificationAvatarSupport() { }

    /** Capture the best Android-exposed identity image and persist it for saved history. */
    public static void captureAsync(@NonNull Context context, @NonNull String notificationId,
                                    @NonNull StatusBarNotification sbn) {
        if (TextUtils.isEmpty(notificationId)) return;
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            if (captureNow(app, notificationId, sbn)) {
                cleanup(app);
                app.sendBroadcast(MainActivity.internalBroadcast(app, MainActivity.LOAD_OVER));
            }
        });
    }

    /**
     * Re-scan active notifications, then link cached conversation identities/shortcut icons to the
     * newest 200 saved history records. No network scraping is used; everything comes from Android
     * notification/shortcut metadata and is cached locally as PNG files.
     */
    public static void loadHistoryAvatarsAsync(@NonNull Context context,
                                               @Nullable LoadCallback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            int fresh = 0;
            StatusBarNotification[] active = NotificationListener.activeNotificationsSnapshot();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (sbn == null) continue;
                    if (captureNow(app, NotificationListener.getTimelineId(sbn), sbn)) fresh++;
                }
            }

            List<NotificationHistoryRecord> records = SmartStateStore.queryNotifications(
                    app, null, null, HISTORY_SCAN_LIMIT);
            int linked = 0;
            for (NotificationHistoryRecord record : records) {
                if (record == null || TextUtils.isEmpty(record.notificationId)) continue;
                if (avatarPath(app, record.notificationId) != null) {
                    touchAvatar(app, record.notificationId);
                    linked++;
                    continue;
                }

                String cached = identityAvatarPath(app, record.packageName, record.title,
                        record.shortcutId);
                if (cached == null) {
                    Drawable shortcut = historicalShortcutAvatar(app, record);
                    if (shortcut != null) {
                        cached = persistIdentityBitmap(app, record.packageName, record.title,
                                record.shortcutId, drawableToBitmap(shortcut));
                        if (cached != null) fresh++;
                    }
                }
                if (cached != null) {
                    SharedPreferences.Editor editor = prefs(app).edit()
                            .putString(record.notificationId + "|image", cached)
                            .putLong(record.notificationId + "|seen", System.currentTimeMillis());
                    editor.apply();
                    touchFile(cached);
                    linked++;
                }
            }
            cleanup(app);
            app.sendBroadcast(MainActivity.internalBroadcast(app, MainActivity.LOAD_OVER));
            if (callback != null) {
                int scanned = records.size();
                int linkedFinal = linked;
                int freshFinal = fresh;
                new Handler(Looper.getMainLooper()).post(
                        () -> callback.onFinished(scanned, linkedFinal, freshFinal));
            }
        });
    }

    private static boolean captureNow(@NonNull Context context, @NonNull String notificationId,
                                      @NonNull StatusBarNotification sbn) {
        Drawable avatar = extractAvatar(context, sbn);
        if (avatar == null) return false;
        Notification notification = sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;
        CharSequence title = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE);
        String shortcutId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notification != null
                ? notification.getShortcutId() : null;
        Bitmap bitmap = drawableToBitmap(avatar);
        String path = persistIdentityBitmap(context, sbn.getPackageName(),
                title == null ? "" : title.toString(), shortcutId, bitmap);
        if (path == null) return false;
        if (bitmap != null) MEMORY_CACHE.put(notificationId, bitmap);
        prefs(context).edit()
                .putString(notificationId + "|image", path)
                .putLong(notificationId + "|seen", System.currentTimeMillis())
                .apply();
        touchFile(path);
        return true;
    }

    /** Return only a captured identity avatar; callers keep their existing app-icon fallback. */
    @Nullable
    public static Drawable avatar(@Nullable Context context, @Nullable String notificationId) {
        if (context == null || TextUtils.isEmpty(notificationId)) return null;
        Bitmap bitmap = MEMORY_CACHE.get(notificationId);
        if (bitmap != null) return new BitmapDrawable(context.getResources(), bitmap);
        String path = avatarPath(context, notificationId);
        if (path == null) return null;
        bitmap = BitmapFactory.decodeFile(path);
        if (bitmap == null) {
            prefs(context).edit().remove(notificationId + "|image").apply();
            return null;
        }
        MEMORY_CACHE.put(notificationId, bitmap);
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> touchAvatar(app, notificationId));
        return new BitmapDrawable(context.getResources(), bitmap);
    }

    /**
     * Release only rebuildable in-memory state. Pending notification work is also discarded under
     * severe pressure so queued StatusBarNotification objects cannot keep large extras/bitmaps alive.
     */
    public static void trimMemory(boolean severe) {
        if (severe) {
            EXECUTOR.getQueue().clear();
            MEMORY_CACHE.evictAll();
        } else {
            MEMORY_CACHE.trimToSize(MEMORY_CACHE_BYTES / 2);
        }
    }

    @Nullable
    private static String avatarPath(@NonNull Context context, @NonNull String notificationId) {
        String path = prefs(context).getString(notificationId + "|image", null);
        if (TextUtils.isEmpty(path) || !new File(path).isFile()) return null;
        return path;
    }

    private static void touchAvatar(@NonNull Context context, @NonNull String notificationId) {
        String path = avatarPath(context, notificationId);
        if (path != null) touchFile(path);
        prefs(context).edit().putLong(notificationId + "|seen", System.currentTimeMillis()).apply();
    }

    @Nullable
    private static Drawable extractAvatar(@NonNull Context context,
                                          @NonNull StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) return null;

        Drawable personAvatar = personAvatar(context, notification);
        if (personAvatar != null) return personAvatar;

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
        return shortcutAvatar(context, sbn, notification);
    }

    @Nullable
    private static Drawable personAvatar(@NonNull Context context,
                                         @NonNull Notification notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
        Bundle extras = notification.extras;
        if (extras == null) return null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Parcelable[] bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (bundles != null) {
                List<Notification.MessagingStyle.Message> messages =
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles);
                if (messages != null) {
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        Notification.MessagingStyle.Message message = messages.get(i);
                        if (message == null) continue;
                        Drawable avatar = drawableFromPerson(context, message.getSenderPerson());
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
        return shortcutAvatar(context, sbn.getUser(), sbn.getPackageName(),
                notification.getShortcutId());
    }

    @Nullable
    private static Drawable historicalShortcutAvatar(@NonNull Context context,
                                                     @NonNull NotificationHistoryRecord record) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || TextUtils.isEmpty(record.shortcutId)
                || TextUtils.isEmpty(record.packageName) || record.userSerial < 0) return null;
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (userManager == null) return null;
        UserHandle user = userManager.getUserForSerialNumber(record.userSerial);
        return user == null ? null
                : shortcutAvatar(context, user, record.packageName, record.shortcutId);
    }

    @Nullable
    private static Drawable shortcutAvatar(@NonNull Context context, @NonNull UserHandle user,
                                           @NonNull String packageName, @NonNull String shortcutId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps == null || !launcherApps.hasShortcutHostPermission()) return null;
        try {
            ShortcutInfo shortcut = ShortcutUtil.getShortCut(context, user, packageName, shortcutId);
            if (shortcut == null) return null;
            return launcherApps.getShortcutIconDrawable(shortcut,
                    context.getResources().getDisplayMetrics().densityDpi);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to resolve conversation shortcut avatar", e);
            return null;
        }
    }

    @Nullable
    private static String identityAvatarPath(@NonNull Context context, @Nullable String packageName,
                                             @Nullable String title, @Nullable String shortcutId) {
        SharedPreferences preferences = prefs(context);
        if (!TextUtils.isEmpty(shortcutId)) {
            String shortcutPath = preferences.getString(
                    aliasKey(packageName, null, shortcutId), null);
            if (validFile(shortcutPath)) return shortcutPath;
        }
        if (!TextUtils.isEmpty(title)) {
            String titlePath = preferences.getString(aliasKey(packageName, title, null), null);
            if (validFile(titlePath)) return titlePath;
        }
        return null;
    }

    @Nullable
    private static String persistIdentityBitmap(@NonNull Context context,
                                                @Nullable String packageName,
                                                @Nullable String title,
                                                @Nullable String shortcutId,
                                                @Nullable Bitmap bitmap) {
        if (bitmap == null) return null;
        String stableIdentity = !TextUtils.isEmpty(shortcutId)
                ? safe(packageName) + "|shortcut|" + shortcutId
                : safe(packageName) + "|title|" + normalize(title);
        if (stableIdentity.endsWith("|title|")) return null;
        File file = identityFile(context, stableIdentity);
        if (!writeBitmap(file, bitmap)) return null;
        String path = file.getAbsolutePath();
        SharedPreferences.Editor editor = prefs(context).edit();
        if (!TextUtils.isEmpty(shortcutId)) {
            editor.putString(aliasKey(packageName, null, shortcutId), path);
        }
        if (!TextUtils.isEmpty(title)) {
            editor.putString(aliasKey(packageName, title, null), path);
        }
        editor.apply();
        return path;
    }

    private static String aliasKey(@Nullable String packageName, @Nullable String title,
                                   @Nullable String shortcutId) {
        if (!TextUtils.isEmpty(shortcutId)) {
            return "alias|shortcut|" + digest(safe(packageName) + "|" + shortcutId);
        }
        return "alias|title|" + digest(safe(packageName) + "|" + normalize(title));
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean validFile(@Nullable String path) {
        return !TextUtils.isEmpty(path) && new File(path).isFile();
    }

    private static void touchFile(@Nullable String path) {
        if (TextUtils.isEmpty(path)) return;
        File file = new File(path);
        if (file.isFile()) file.setLastModified(System.currentTimeMillis());
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

    private static File identityFile(@NonNull Context context, @NonNull String identity) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Unable to create notification avatar directory");
        }
        return new File(dir, digest(identity) + ".png");
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
