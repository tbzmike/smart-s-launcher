package fr.neamar.kiss.notification;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import fr.neamar.kiss.IconsHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.utils.UserHandle;

/**
 * Central app-identity icon resolver for notification surfaces.
 *
 * When a custom icon pack is selected, notification rows must use the same packed app identity
 * as normal launcher results. Without a custom pack we preserve notification avatars when Android
 * supplied one, then fall back through the normal launcher icon pipeline.
 */
public final class NotificationIdentityIcon {
    private NotificationIdentityIcon() {
    }

    @Nullable
    public static Drawable resolve(Context context, @Nullable String notificationId,
                                   @Nullable String packageName) {
        IconsHandler icons = KissApplication.getApplication(context).getIconsHandler();

        if (icons.isCustomIconPackActive() && !TextUtils.isEmpty(packageName)) {
            Drawable packed = icons.getDrawableIconForPackageNameUncached(packageName, UserHandle.OWNER);
            if (packed != null) return packed;
        }

        if (!TextUtils.isEmpty(notificationId)) {
            Drawable avatar = NotificationAvatarSupport.avatar(context, notificationId);
            if (avatar != null) return avatar;
        }

        if (!TextUtils.isEmpty(packageName)) {
            return icons.getDrawableIconForPackageNameUncached(packageName, UserHandle.OWNER);
        }
        return null;
    }
}
