package fr.neamar.kiss.pojo;

import androidx.annotation.NonNull;

public final class NotificationPojo extends SettingPojo {
    public final String packageName;
    public final String appName;
    public final String groupKey;
    public final int notificationCount;
    public final String latestTitle;
    public final String latestText;
    public final long postTime;

    public NotificationPojo(@NonNull String id,
                            @NonNull String packageName,
                            @NonNull String appName,
                            @NonNull String groupKey,
                            int notificationCount,
                            @NonNull String latestTitle,
                            @NonNull String latestText,
                            long postTime) {
        super(id, "", -1);
        this.packageName = packageName;
        this.appName = appName;
        this.groupKey = groupKey;
        this.notificationCount = notificationCount;
        this.latestTitle = latestTitle;
        this.latestText = latestText;
        this.postTime = postTime;

        String searchable = appName;
        if (!latestTitle.isEmpty()) searchable += " " + latestTitle;
        if (!latestText.isEmpty()) searchable += " " + latestText;
        setName(searchable.trim(), true);
    }

    public String getSummary() {
        return notificationCount == 1 ? "1 notification" : notificationCount + " notifications";
    }
}
