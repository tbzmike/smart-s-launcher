package fr.neamar.kiss.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.NotificationPojo;

/**
 * Builds a compact, non-destructive presentation for conversation/social notifications.
 * Detection is based on Android's SOCIAL app category or a real notification reply action;
 * it does not replace notification history, native RemoteViews, or existing actions.
 */
public final class SocialMessagePresentation {
    public final boolean message;
    public final String headline;
    public final String preview;
    public final String latestNotificationId;

    private SocialMessagePresentation(boolean message, String headline, String preview,
                                      String latestNotificationId) {
        this.message = message;
        this.headline = headline;
        this.preview = preview;
        this.latestNotificationId = latestNotificationId;
    }

    public static SocialMessagePresentation resolve(Context context, NotificationPojo pojo) {
        if (context == null || pojo == null) return none();

        java.util.List<NotificationListener.NotificationSnapshot> active =
                NotificationListener.getGroupNotifications(context, pojo.groupKey);
        NotificationListener.NotificationSnapshot latest = active.isEmpty() ? null : active.get(0);

        String notificationId = latest == null ? "" : latest.id;
        String title = clean(latest == null ? pojo.latestTitle : latest.title);
        String text = clean(latest == null ? pojo.latestText : latest.text);

        boolean socialCategory = isSocialCategory(context, pojo.packageName);
        boolean replyable = !TextUtils.isEmpty(notificationId)
                && NotificationListener.hasReplyAction(context, notificationId);

        // A SOCIAL app alone is not enough: promotions and service notices from social apps must
        // keep the ordinary notification card. A real message must contain message-like content,
        // and replyable notifications are strong Android-level conversation evidence.
        boolean hasContent = !TextUtils.isEmpty(title) || !TextUtils.isEmpty(text);
        boolean isMessage = hasContent && (replyable || (socialCategory && looksLikePersonOrConversation(title, text)));
        if (!isMessage) return none();

        String sender = extractSender(title, pojo.appName);
        String headline = pojo.appName + " message";
        if (!TextUtils.isEmpty(sender)) headline += " from \"" + sender + "\"";

        String preview = text;
        if (TextUtils.isEmpty(preview) || TextUtils.equals(preview, title)) preview = title;
        return new SocialMessagePresentation(true, headline, preview, notificationId);
    }

    private static boolean isSocialCategory(Context context, String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    && info.category == ApplicationInfo.CATEGORY_SOCIAL;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean looksLikePersonOrConversation(String title, String text) {
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(text)) return false;
        String lower = title.toLowerCase(java.util.Locale.ROOT);
        // Reject obvious aggregate/promotional titles rather than pretending they are senders.
        return !(lower.matches(".*\\b[0-9]+\\s+(new\\s+)?(messages?|notifications?|updates?)\\b.*")
                || lower.contains("recommended") || lower.contains("suggested")
                || lower.contains("promotion") || lower.contains("trending"));
    }

    private static String extractSender(String title, String appName) {
        String sender = clean(title);
        if (TextUtils.isEmpty(sender) || TextUtils.equals(sender, appName)) return "";
        String lower = sender.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("new message") || lower.contains("messages from")
                || lower.contains("notification") || lower.contains("recommended")) return "";
        return sender;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static SocialMessagePresentation none() {
        return new SocialMessagePresentation(false, "", "", "");
    }
}
