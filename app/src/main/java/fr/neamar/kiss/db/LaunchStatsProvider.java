package fr.neamar.kiss.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads launch metadata in one grouped query so list rendering never performs a database query
 * per row. The caller should run this method off the main thread.
 */
public final class LaunchStatsProvider {
    private LaunchStatsProvider() {
    }

    public static final class LaunchStats {
        public final long lastLaunchTime;
        public final int launchesToday;
        public final int totalLaunches;

        LaunchStats(long lastLaunchTime, int launchesToday, int totalLaunches) {
            this.lastLaunchTime = lastLaunchTime;
            this.launchesToday = launchesToday;
            this.totalLaunches = totalLaunches;
        }
    }

    @NonNull
    public static Map<String, LaunchStats> loadAll(@NonNull Context context) {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        long startOfToday = start.getTimeInMillis();

        HashMap<String, LaunchStats> stats = new HashMap<>();
        DB helper = new DB(context.getApplicationContext());
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            String sql = "SELECT record, MAX(timeStamp), "
                    + "SUM(CASE WHEN timeStamp >= ? THEN 1 ELSE 0 END), COUNT(*) "
                    + "FROM history GROUP BY record";
            try (Cursor cursor = db.rawQuery(sql, new String[]{Long.toString(startOfToday)})) {
                while (cursor.moveToNext()) {
                    stats.put(cursor.getString(0), new LaunchStats(
                            cursor.getLong(1), cursor.getInt(2), cursor.getInt(3)));
                }
            }
        } finally {
            helper.close();
        }
        return stats;
    }
}
