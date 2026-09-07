package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;

/** Persistent click count for exact launcher tiles that are not represented by normal history. */
public final class TileLaunchCounter {
    private static final String PREFS = "smart-s-tile-launch-counts";
    private static final String COUNT_PREFIX = "count:";
    private static final Object LOCK = new Object();

    private TileLaunchCounter() {}

    /** Record one explicit click on an exact tile. */
    public static void record(@NonNull Context context, @NonNull Pojo pojo) {
        String key = storageKey(pojo);
        if (TextUtils.isEmpty(key)) return;
        synchronized (LOCK) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String countKey = COUNT_PREFIX + key;
            long current = Math.max(0L, prefs.getLong(countKey, 0L));
            long next = current == Long.MAX_VALUE ? Long.MAX_VALUE : current + 1L;
            prefs.edit().putLong(countKey, next).apply();
        }
    }

    /** Return the number of clicks recorded for this exact tile since tracking became available. */
    public static long getTotal(@NonNull Context context, @NonNull Pojo pojo) {
        String key = storageKey(pojo);
        if (TextUtils.isEmpty(key)) return 0L;
        synchronized (LOCK) {
            return Math.max(0L, context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(COUNT_PREFIX + key, 0L));
        }
    }

    /**
     * A notification ID can be reused by an app. Including post time keeps a newly posted
     * notification from inheriting the click count of an older notification with the same ID.
     */
    @NonNull
    static String storageKey(@NonNull Pojo pojo) {
        if (pojo instanceof NotificationPojo) {
            NotificationPojo notification = (NotificationPojo) pojo;
            return notification.id + "|post:" + Math.max(0L, notification.postTime);
        }
        String historyId = pojo.getHistoryId();
        return historyId == null ? "" : historyId;
    }
}
