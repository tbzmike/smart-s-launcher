package fr.neamar.kiss.ui;

/** Pure policy kept separate so notification-vs-app identity and flashing rules stay testable. */
public final class NotificationBellPolicy {
    private NotificationBellPolicy() {}

    public static boolean shouldShow(boolean notificationEvent, boolean persistedNotificationEvent) {
        return notificationEvent || persistedNotificationEvent;
    }

    public static boolean shouldFlash(boolean showBell, boolean unread, boolean verticalList) {
        return showBell && unread && verticalList;
    }
}
