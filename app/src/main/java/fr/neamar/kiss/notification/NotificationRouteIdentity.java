package fr.neamar.kiss.notification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable, message-specific identity for an Android-managed saved notification route. */
public final class NotificationRouteIdentity {
    private static final int TOKEN_LENGTH = 64;

    private NotificationRouteIdentity() {}

    @NonNull
    public static String create(@Nullable String notificationId, long postTime) {
        if (notificationId == null || notificationId.isEmpty()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(notificationId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(postTime).getBytes(StandardCharsets.UTF_8));
            byte[] value = digest.digest();
            StringBuilder token = new StringBuilder(TOKEN_LENGTH);
            for (byte item : value) {
                int unsigned = item & 0xff;
                if (unsigned < 0x10) token.append('0');
                token.append(Integer.toHexString(unsigned));
            }
            return token.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static boolean isValid(@Nullable String token) {
        if (token == null || token.length() != TOKEN_LENGTH) return false;
        for (int i = 0; i < token.length(); i++) {
            char value = token.charAt(i);
            if ((value < '0' || value > '9') && (value < 'a' || value > 'f')) return false;
        }
        return true;
    }

    public static int requestCode(@NonNull String token) {
        if (!isValid(token)) throw new IllegalArgumentException("Invalid notification route token");
        return token.hashCode();
    }
}
