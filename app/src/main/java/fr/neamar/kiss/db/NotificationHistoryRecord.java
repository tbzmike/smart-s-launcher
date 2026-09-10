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
    /** Verified explicit route captured from the app-published conversation shortcut. */
    public String routeUri;
    /** Android-managed relay identity retaining the posting app's original content PendingIntent. */
    public String pendingIntentToken;
    /** App-published conversation locus used to re-resolve a renamed/replaced shortcut. */
    public String locusId;
}
