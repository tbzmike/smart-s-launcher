package fr.neamar.kiss.notification;

import java.util.Locale;

/**
 * Conservative identity key for Android-exposed social notification artwork.
 * Only exact sender/conversation titles from the SAME package can share avatars.
 * Generic app/aggregate titles are not persons and must never reuse a photo.
 */
final class NotificationAvatarKeyPolicy {
    private NotificationAvatarKeyPolicy() {}

    static String titleKey(String packageName, String raw) {
        if (raw == null || packageName == null || packageName.trim().isEmpty()) return "";
        String title = raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String pkg = packageName.toLowerCase(Locale.ROOT);
        String prefix = "";
        if (pkg.startsWith("com.whatsapp")) prefix = "whatsapp";
        else if (pkg.startsWith("org.telegram")) prefix = "telegram";
        else if (pkg.startsWith("com.instagram")) prefix = "instagram";
        else if (pkg.startsWith("com.facebook.orca")) prefix = "messenger";
        else if (pkg.startsWith("com.facebook")) prefix = "facebook";
        else if (pkg.startsWith("org.thoughtcrime.securesms")) prefix = "signal";
        else if (pkg.startsWith("com.twitter")) prefix = "x";

        if (!prefix.isEmpty() && title.startsWith(prefix + ": ")) {
            title = title.substring(prefix.length() + 2).trim();
        }
        if (title.length() < 3
                || title.equals(prefix)
                || title.matches("([0-9]+ )?(new )?(messages?|notifications?|updates?)")
                || title.matches("[0-9]+ (new )?messages?")
                || title.equals("whatsapp")
                || title.equals("facebook")
                || title.equals("messenger")
                || title.equals("instagram")
                || title.equals("telegram")
                || title.equals("signal")
                || title.equals("twitter")
                || title.equals("new message")
                || title.equals("notifications")) {
            return "";
        }
        return title;
    }
}
