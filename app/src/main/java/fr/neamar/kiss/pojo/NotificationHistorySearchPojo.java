package fr.neamar.kiss.pojo;

import androidx.annotation.NonNull;

import java.util.Locale;

import fr.neamar.kiss.NotificationHistoryActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.db.NotificationHistoryRecord;

/** Search-only link from a matching persisted notification into Smart S notification history. */
public final class NotificationHistorySearchPojo extends SettingPojo {
    private static final String SEARCH_SCHEME = "notification-history-search://";
    private static final String SETTING_SCHEME = "setting://";

    public final long historyDbId;
    public final String sourcePackageName;
    public final String sourceNotificationId;
    public final long postTime;

    public NotificationHistorySearchPojo(@NonNull String launcherPackageName,
                                         @NonNull NotificationHistoryRecord record) {
        super(SEARCH_SCHEME + record.dbId,
                NotificationHistoryActivity.class.getName(),
                launcherPackageName,
                R.drawable.setting_apps);
        historyDbId = record.dbId;
        sourcePackageName = safe(record.packageName);
        sourceNotificationId = safe(record.notificationId);
        postTime = record.postTime;
        setName(buildDisplayName(record), true);
    }

    /**
     * Keep adapter IDs unique per matching notification while launcher-history persistence resolves
     * back to the existing Notification history destination instead of leaving stale search IDs.
     */
    @Override
    public String getHistoryId() {
        return SETTING_SCHEME + NotificationHistoryActivity.class.getName().toLowerCase(Locale.ENGLISH);
    }

    private static String buildDisplayName(NotificationHistoryRecord record) {
        String app = safe(record.appName);
        if (app.isEmpty()) app = safe(record.packageName);
        String title = safe(record.title);
        String body = safe(record.text);

        StringBuilder value = new StringBuilder("Notification history");
        if (!app.isEmpty()) value.append(" · ").append(app);
        if (!title.isEmpty()) value.append(" · ").append(title);
        if (!body.isEmpty() && !body.equals(title)) value.append(" · ").append(body);
        return value.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
