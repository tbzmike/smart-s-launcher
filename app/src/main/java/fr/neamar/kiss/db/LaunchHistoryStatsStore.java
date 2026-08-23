package fr.neamar.kiss.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/** Lightweight grouped launch statistics backed by the existing KISS history table. */
public final class LaunchHistoryStatsStore {
    private LaunchHistoryStatsStore() {}

    public static final class Stats {
        public final long lastLaunchTime;
        public final int launchesToday;

        Stats(long lastLaunchTime, int launchesToday) {
            this.lastLaunchTime = lastLaunchTime;
            this.launchesToday = launchesToday;
        }
    }

    /**
     * Returns one stats object per history record using a single grouped database query.
     * The day boundary follows the device's current local timezone.
     */
    @NonNull
    public static Map<String, Stats> getAll(@NonNull Context context) {
        Calendar midnight = Calendar.getInstance();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        long startOfToday = midnight.getTimeInMillis();

        Map<String, Stats> result = new HashMap<>();
        SQLiteDatabase db = new DB(context.getApplicationContext()).getReadableDatabase();
        String sql = "SELECT record, MAX(timeStamp), "
                + "SUM(CASE WHEN timeStamp >= ? THEN 1 ELSE 0 END) "
                + "FROM history GROUP BY record";
        try (Cursor cursor = db.rawQuery(sql, new String[]{Long.toString(startOfToday)})) {
            while (cursor.moveToNext()) {
                String record = cursor.getString(0);
                long lastLaunch = cursor.getLong(1);
                int todayCount = cursor.getInt(2);
                if (record != null) {
                    result.put(record, new Stats(lastLaunch, todayCount));
                }
            }
        }
        db.close();
        return result;
    }
}
