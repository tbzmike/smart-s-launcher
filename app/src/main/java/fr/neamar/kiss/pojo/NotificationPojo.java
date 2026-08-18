package fr.neamar.kiss.pojo;

import androidx.annotation.NonNull;

public final class NotificationPojo extends Pojo {
    public final String packageName;
    public final String appName;
    public final String title;
    public final String text;
    public final long postTime;

    public NotificationPojo(@NonNull String id,
                            @NonNull String packageName,
                            @NonNull String appName,
                            @NonNull String title,
                            @NonNull String text,
                            long postTime) {
        super(id);
        this.packageName = packageName;
        this.appName = appName;
        this.title = title;
        this.text = text;
        this.postTime = postTime;

        String searchable = appName;
        if (!title.isEmpty()) searchable += " " + title;
        if (!text.isEmpty()) searchable += " " + text;
        setName(searchable.trim(), true);
    }

    public String getDisplayTitle() {
        return title.isEmpty() ? appName : title;
    }

    public String getDisplayText() {
        return text;
    }
}
