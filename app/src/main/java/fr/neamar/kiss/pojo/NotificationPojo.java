package fr.neamar.kiss.pojo;

import androidx.annotation.NonNull;

public final class NotificationPojo extends SettingPojo {
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
        super(id, "", packageName, -1);
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

    public String getPreview() {
        String title = latestTitle.trim();
        String text = latestText.trim();
        if (title.isEmpty()) return text;
        if (text.isEmpty() || title.equals(text)) return title;
        if (title.contains(text)) return title;
        if (text.contains(title)) return text;
        return title + ": " + text;
    }

    public String getSummary() {
        String preview = getPreview();
        if (!preview.isEmpty()) return preview;
        return notificationCount == 1 ? "1 notification" : notificationCount + " notifications";
    }
}
