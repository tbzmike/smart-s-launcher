package fr.neamar.kiss.db;

public class NotificationHistoryRecord {
    public long dbId;
    public String notificationId;
    public String packageName;
    public String appName;
    public String title;
    public String text;
    public long postTime;
    public boolean permanent;
    /** Stable app-published conversation shortcut captured while the notification was live. */
    public String shortcutId;
    /** Android profile serial that posted the notification; -1 when unavailable. */
    public long userSerial = -1L;
}
