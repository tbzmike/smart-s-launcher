package fr.neamar.kiss.forwarder;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.List;

import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.utils.Log;

/**
 * Reads only information Android already exposes to Smart S through its notification-listener
 * permission. No app-private databases, files or network scraping are used.
 */
final class LiveTileDataProvider {
    private static final String TAG = LiveTileDataProvider.class.getSimpleName();

    static final class LiveTileData {
        final Drawable artwork;
        final String title;
        final String text;
        final String subText;
        final int progress;
        final int progressMax;
        final boolean progressIndeterminate;

        LiveTileData(Drawable artwork, String title, String text, String subText,
                     int progress, int progressMax, boolean progressIndeterminate) {
            this.artwork = artwork;
            this.title = title;
            this.text = text;
            this.subText = subText;
            this.progress = progress;
            this.progressMax = progressMax;
            this.progressIndeterminate = progressIndeterminate;
        }

        boolean hasVisibleInformation() {
            return artwork != null || !TextUtils.isEmpty(title) || !TextUtils.isEmpty(text)
                    || !TextUtils.isEmpty(subText) || progressMax > 0 || progressIndeterminate;
        }
    }

    private LiveTileDataProvider() {
    }

    @Nullable
    static LiveTileData latestForPackage(Context context, String packageName) {
        if (TextUtils.isEmpty(packageName)) return null;
        NotificationListener listener = getListener();
        if (listener == null) return null;

        StatusBarNotification[] active;
        try {
            active = listener.getActiveNotifications();
        } catch (SecurityException | RuntimeException e) {
            Log.w(TAG, "Unable to read active notifications", e);
            return null;
        }
        if (active == null || active.length == 0) return null;

        StatusBarNotification latest = null;
        for (StatusBarNotification sbn : active) {
            if (sbn == null || !packageName.equals(sbn.getPackageName())) continue;
            if (latest == null || sbn.getPostTime() > latest.getPostTime()) latest = sbn;
        }
        if (latest == null || latest.getNotification() == null) return null;

        Notification notification = latest.getNotification();
        Bundle extras = notification.extras;
        Drawable artwork = extractArtwork(context, notification, extras);
        String title = firstText(extras, Notification.EXTRA_CONVERSATION_TITLE,
                Notification.EXTRA_TITLE_BIG, Notification.EXTRA_TITLE);
        String text = firstText(extras, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_TEXT);
        String subText = firstText(extras, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT);

        int progress = extras == null ? 0 : extras.getInt(Notification.EXTRA_PROGRESS, 0);
        int progressMax = extras == null ? 0 : extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        boolean indeterminate = extras != null
                && extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);

        LiveTileData data = new LiveTileData(artwork, title, text, subText,
                progress, progressMax, indeterminate);
        return data.hasVisibleInformation() ? data : null;
    }

    @Nullable
    private static NotificationListener getListener() {
        try {
            Field field = NotificationListener.class.getDeclaredField("instance");
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof NotificationListener ? (NotificationListener) value : null;
        } catch (NoSuchFieldException | IllegalAccessException | RuntimeException e) {
            Log.w(TAG, "Notification listener instance unavailable", e);
            return null;
        }
    }

    @Nullable
    private static Drawable extractArtwork(Context context, Notification notification, Bundle extras) {
        if (extras != null) {
            Drawable messaging = extractMessagingPersonArtwork(context, extras);
            if (messaging != null) return messaging;

            Drawable picture = drawableFromValue(context, extras.get(Notification.EXTRA_PICTURE));
            if (picture != null) return picture;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Drawable pictureIcon = drawableFromValue(context,
                        extras.get(Notification.EXTRA_PICTURE_ICON));
                if (pictureIcon != null) return pictureIcon;
            }

            Drawable largeExtra = drawableFromValue(context, extras.get(Notification.EXTRA_LARGE_ICON));
            if (largeExtra != null) return largeExtra;
            Drawable largeBig = drawableFromValue(context, extras.get(Notification.EXTRA_LARGE_ICON_BIG));
            if (largeBig != null) return largeBig;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notification.getLargeIcon() != null) {
            try {
                return notification.getLargeIcon().loadDrawable(context);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to load notification large icon", e);
            }
        }
        return null;
    }

    @Nullable
    private static Drawable extractMessagingPersonArtwork(Context context, Bundle extras) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
        Parcelable[] bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (bundles == null || bundles.length == 0) return null;
        try {
            List<Notification.MessagingStyle.Message> messages =
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles);
            if (messages == null) return null;
            for (int i = messages.size() - 1; i >= 0; i--) {
                Notification.MessagingStyle.Message message = messages.get(i);
                if (message == null) continue;
                Person sender = message.getSenderPerson();
                if (sender == null || sender.getIcon() == null) continue;
                Drawable drawable = sender.getIcon().loadDrawable(context);
                if (drawable != null) return drawable;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read messaging person artwork", e);
        }
        return null;
    }

    @Nullable
    private static Drawable drawableFromValue(Context context, Object value) {
        if (value instanceof Drawable) return (Drawable) value;
        if (value instanceof Bitmap) {
            return new BitmapDrawable(context.getResources(), (Bitmap) value);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && value instanceof Icon) {
            try {
                return ((Icon) value).loadDrawable(context);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to load notification icon", e);
            }
        }
        return null;
    }

    private static String firstText(Bundle extras, String... keys) {
        if (extras == null) return "";
        for (String key : keys) {
            CharSequence value = extras.getCharSequence(key);
            if (value != null) {
                String clean = value.toString().trim();
                if (!clean.isEmpty()) return clean;
            }
        }
        return "";
    }
}
