package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;

import fr.neamar.kiss.notification.NotificationAvatarSupport;
import fr.neamar.kiss.notification.NotificationIdentityIcon;

/** Ensures avatar shown in History can reappear in search under a different notification ID. */
public final class SocialAvatarIdentityRegressionTest {
    @Test public void sharesCapturedPersonPhotoByPackageAndExactTitle() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String packageName = "com.whatsapp.regression";
        String title = "Avatar regression person unique 2026";
        Bitmap photo = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888);
        photo.eraseColor(Color.MAGENTA);
        Method persist = NotificationAvatarSupport.class.getDeclaredMethod(
                "persistIdentityBitmap", Context.class, String.class, String.class,
                String.class, Bitmap.class);
        persist.setAccessible(true);
        String path = (String) persist.invoke(null, app, packageName, title, null, photo);
        assertNotNull("Avatar should persist", path);
        try {
            Drawable fromOtherNotificationId = NotificationAvatarSupport.avatar(
                    app, "notification://avatar-regression-other-id",
                    packageName, "WhatsApp: " + title.toUpperCase(java.util.Locale.ROOT), null);
            assertNotNull("Title alias should restore identity artwork", fromOtherNotificationId);
            Drawable fromHistory = NotificationIdentityIcon.resolve(
                    app, "notification://avatar-regression-third-id",
                    packageName, title, null);
            assertNotNull("History must display same person's cached photo", fromHistory);
            Drawable unrelated = NotificationAvatarSupport.avatar(
                    app, "notification://avatar-regression-unrelated",
                    "org.telegram.messenger", title, null);
            assertNull("Never share avatars across different apps", unrelated);
        } finally {
            photo.recycle();
            new File(path).delete();
        }
    }
}
