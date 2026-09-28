package fr.neamar.kiss.notification;

/**
 * Stable identity for artwork/control state that belongs to one exact Android notification post.
 *
 * Package identity alone is intentionally insufficient: one app can expose calls, text messages,
 * audio/video playback and ordinary launcher history at the same time. Post time is included so an
 * app reusing the same Android notification slot cannot leak the previous event's artwork.
 */
public final class MediaEventIdentity {
    private MediaEventIdentity() { }

    public static String create(String packageName, String notificationId, long postTime) {
        if (packageName == null || packageName.isEmpty()
                || notificationId == null || notificationId.isEmpty() || postTime <= 0L) {
            return "";
        }
        return packageName + '\n' + notificationId + '\n' + postTime;
    }
}
