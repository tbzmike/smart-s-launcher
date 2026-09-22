package fr.neamar.kiss.notification;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import fr.neamar.kiss.IconsHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.utils.UserHandle;

/**
 * Central identity-artwork resolver for notification/person surfaces.
 *
 * Person-specific artwork (sender avatar, profile photo, conversation shortcut icon) is content,
 * not an app launcher icon. It must therefore stay above icon-pack substitution. Icon packs only
 * replace the generic app-identity fallback when Android did not expose person-specific artwork.
 */
public final class NotificationIdentityIcon {
    private NotificationIdentityIcon() {
    }

    @Nullable
    public static Drawable resolve(Context context, @Nullable String notificationId,
                                   @Nullable String packageName) {
        IconsHandler icons = KissApplication.getApplication(context).getIconsHandler();

        // A sender/contact/profile image is the identity of the conversation/person. Never replace
        // it with the application's themed launcher icon merely because an icon pack is active.
        if (!TextUtils.isEmpty(notificationId)) {
            Drawable avatar = NotificationAvatarSupport.avatar(context, notificationId);
            if (avatar != null) return avatar;
        }

        // Only the generic application fallback is themed by the selected icon pack.
        if (!TextUtils.isEmpty(packageName)) {
            return icons.getDrawableIconForPackageNameUncached(packageName, UserHandle.OWNER);
        }
        return null;
    }
}
