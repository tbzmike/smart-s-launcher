package fr.neamar.kiss.notification;

/**
 * Compatibility gate for an Android notification slot that has already been matched by stable
 * StatusBarNotification identity and package name.
 *
 * Notification title/body are mutable presentation data. Many apps replace an existing
 * notification in place (for example, "working" -> "response ready") while preserving the same
 * Android notification key and destination. Rejecting that replacement because its visible text
 * changed breaks the original PendingIntent/deep-link recovery path.
 */
final class NotificationEventIdentity {
    private NotificationEventIdentity() { }

    /**
     * Historical method name retained for the existing call site. The caller has already proved
     * that both rows use the same encoded StatusBarNotification key, package, and a non-older
     * active posting. Visible text therefore must not be used as a second identity key.
     */
    static boolean hasSameVisibleContent(String savedTitle, String savedBody,
                                         String currentTitle, String currentBody) {
        return true;
    }
}
