package fr.neamar.kiss.appusage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Local, rolling 365-day store for app usage, screen state and package history. */
public final class AppUsageStore extends SQLiteOpenHelper {
    public static final String KIND_APP_USAGE = "APP_USAGE";
    public static final String KIND_APP_INTERACTION = "APP_INTERACTION";
    public static final String KIND_SHORTCUT = "SHORTCUT";
    public static final String KIND_SCREEN_ON = "SCREEN_ON";
    public static final String KIND_SCREEN_OFF = "SCREEN_OFF";
    public static final String KIND_LOCKED = "LOCKED";
    public static final String KIND_UNLOCKED = "UNLOCKED";
    public static final String KIND_INSTALLED = "APP_INSTALLED";
    public static final String KIND_UPDATED = "APP_UPDATED";
    public static final String KIND_UNINSTALLED = "APP_UNINSTALLED";

    private static final String DB_NAME = "smart_s_app_usage.db";
    private static final int DB_VERSION = 1;
    public static final long RETENTION_MS = 365L * 24L * 60L * 60L * 1000L;

    private static volatile AppUsageStore instance;

    public static AppUsageStore get(@NonNull Context context) {
        if (instance == null) {
            synchronized (AppUsageStore.class) {
                if (instance == null) instance = new AppUsageStore(context.getApplicationContext());
            }
        }
        return instance;
    }

    private AppUsageStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE timeline (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "event_key TEXT NOT NULL UNIQUE," +
                "start_ms INTEGER NOT NULL," +
                "end_ms INTEGER NOT NULL DEFAULT 0," +
                "kind TEXT NOT NULL," +
                "package_name TEXT," +
                "app_label TEXT," +
                "duration_ms INTEGER NOT NULL DEFAULT 0," +
                "is_system INTEGER NOT NULL DEFAULT 0," +
                "detail TEXT," +
                "source TEXT," +
                "source_uri TEXT)");
        db.execSQL("CREATE INDEX idx_usage_timeline_start ON timeline(start_ms DESC)");
        db.execSQL("CREATE INDEX idx_usage_timeline_package ON timeline(package_name)");
        db.execSQL("CREATE INDEX idx_usage_timeline_kind ON timeline(kind)");

        db.execSQL("CREATE TABLE daily_usage (" +
                "day_ms INTEGER NOT NULL," +
                "package_name TEXT NOT NULL," +
                "app_label TEXT," +
                "duration_ms INTEGER NOT NULL DEFAULT 0," +
                "is_system INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(day_ms, package_name))");
        db.execSQL("CREATE INDEX idx_daily_usage_day ON daily_usage(day_ms DESC)");

        db.execSQL("CREATE TABLE package_state (" +
                "package_name TEXT PRIMARY KEY," +
                "app_label TEXT," +
                "is_system INTEGER NOT NULL DEFAULT 0," +
                "first_install_ms INTEGER NOT NULL DEFAULT 0," +
                "last_update_ms INTEGER NOT NULL DEFAULT 0," +
                "source TEXT," +
                "source_uri TEXT)");

        db.execSQL("CREATE TABLE meta (meta_key TEXT PRIMARY KEY, meta_value TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the initial schema.
    }

    public synchronized void putTimeline(@NonNull TimelineEntry entry) {
        ContentValues values = new ContentValues();
        values.put("event_key", entry.eventKey);
        values.put("start_ms", entry.startMs);
        values.put("end_ms", entry.endMs);
        values.put("kind", entry.kind);
        values.put("package_name", entry.packageName);
        values.put("app_label", entry.appLabel);
        values.put("duration_ms", Math.max(0L, entry.durationMs));
        values.put("is_system", entry.systemApp ? 1 : 0);
        values.put("detail", entry.detail);
        values.put("source", entry.source);
        values.put("source_uri", entry.sourceUri);
        getWritableDatabase().insertWithOnConflict(
                "timeline", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void putDailyUsage(long dayMs, @NonNull String packageName,
                                           @Nullable String label, long durationMs,
                                           boolean systemApp) {
        ContentValues values = new ContentValues();
        values.put("day_ms", dayMs);
        values.put("package_name", packageName);
        values.put("app_label", label);
        values.put("duration_ms", Math.max(0L, durationMs));
        values.put("is_system", systemApp ? 1 : 0);
        getWritableDatabase().insertWithOnConflict(
                "daily_usage", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void putPackageState(@NonNull PackageState state) {
        ContentValues values = new ContentValues();
        values.put("package_name", state.packageName);
        values.put("app_label", state.appLabel);
        values.put("is_system", state.systemApp ? 1 : 0);
        values.put("first_install_ms", state.firstInstallMs);
        values.put("last_update_ms", state.lastUpdateMs);
        values.put("source", state.source);
        values.put("source_uri", state.sourceUri);
        getWritableDatabase().insertWithOnConflict(
                "package_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Nullable
    public synchronized PackageState getPackageState(@NonNull String packageName) {
        try (Cursor c = getReadableDatabase().query(
                "package_state",
                new String[]{"package_name", "app_label", "is_system", "first_install_ms",
                        "last_update_ms", "source", "source_uri"},
                "package_name=?", new String[]{packageName}, null, null, null, "1")) {
            if (!c.moveToFirst()) return null;
            return new PackageState(c.getString(0), c.getString(1), c.getInt(2) != 0,
                    c.getLong(3), c.getLong(4), c.getString(5), c.getString(6));
        }
    }

    public synchronized void setMeta(@NonNull String key, long value) {
        ContentValues values = new ContentValues();
        values.put("meta_key", key);
        values.put("meta_value", Long.toString(value));
        getWritableDatabase().insertWithOnConflict(
                "meta", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized long getMeta(@NonNull String key, long fallback) {
        try (Cursor c = getReadableDatabase().query(
                "meta", new String[]{"meta_value"}, "meta_key=?", new String[]{key},
                null, null, null, "1")) {
            if (!c.moveToFirst()) return fallback;
            try {
                return Long.parseLong(c.getString(0));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    @NonNull
    public synchronized List<TimelineEntry> getTimeline(long sinceMs, int limit) {
        List<TimelineEntry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "timeline",
                new String[]{"event_key", "start_ms", "end_ms", "kind", "package_name",
                        "app_label", "duration_ms", "is_system", "detail", "source", "source_uri"},
                "start_ms>=?", new String[]{Long.toString(sinceMs)}, null, null,
                "start_ms DESC", Integer.toString(limit))) {
            while (c.moveToNext()) out.add(readTimeline(c));
        }
        return out;
    }

    @NonNull
    public synchronized List<TimelineEntry> getDailyUsageTimeline(long sinceMs, int limit) {
        List<TimelineEntry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "daily_usage",
                new String[]{"day_ms", "package_name", "app_label", "duration_ms", "is_system"},
                "day_ms>=? AND duration_ms>0", new String[]{Long.toString(sinceMs)}, null, null,
                "day_ms DESC,duration_ms DESC", Integer.toString(limit))) {
            while (c.moveToNext()) {
                long day = c.getLong(0);
                String pkg = c.getString(1);
                out.add(new TimelineEntry(
                        "daily:" + day + ":" + pkg,
                        day + 12L * 60L * 60L * 1000L,
                        0L,
                        "APP_DAILY_USAGE",
                        pkg,
                        c.getString(2),
                        c.getLong(3),
                        c.getInt(4) != 0,
                        "Android daily usage total",
                        null,
                        null));
            }
        }
        return out;
    }

    public synchronized Summary getSummary(long sinceMs, long untilMs) {
        SQLiteDatabase db = getReadableDatabase();
        long appMs = scalarLong(db,
                "SELECT COALESCE(SUM(duration_ms),0) FROM daily_usage WHERE day_ms>=? AND day_ms<?",
                new String[]{Long.toString(startOfDay(sinceMs)), Long.toString(untilMs)});
        long screenOn = scalarLong(db,
                "SELECT COALESCE(SUM(duration_ms),0) FROM timeline WHERE kind=? AND start_ms>=? AND start_ms<?",
                new String[]{KIND_SCREEN_ON, Long.toString(sinceMs), Long.toString(untilMs)});
        long screenOff = scalarLong(db,
                "SELECT COALESCE(SUM(duration_ms),0) FROM timeline WHERE kind=? AND start_ms>=? AND start_ms<?",
                new String[]{KIND_SCREEN_OFF, Long.toString(sinceMs), Long.toString(untilMs)});
        int apps = (int) scalarLong(db,
                "SELECT COUNT(DISTINCT package_name) FROM daily_usage WHERE day_ms>=? AND day_ms<? AND duration_ms>0",
                new String[]{Long.toString(startOfDay(sinceMs)), Long.toString(untilMs)});
        return new Summary(appMs, screenOn, screenOff, apps);
    }

    public synchronized void prune(long nowMs) {
        long cutoff = nowMs - RETENTION_MS;
        SQLiteDatabase db = getWritableDatabase();
        db.delete("timeline", "start_ms<?", new String[]{Long.toString(cutoff)});
        db.delete("daily_usage", "day_ms<?", new String[]{Long.toString(startOfDay(cutoff))});
    }

    private static TimelineEntry readTimeline(Cursor c) {
        return new TimelineEntry(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3),
                c.getString(4), c.getString(5), c.getLong(6), c.getInt(7) != 0,
                c.getString(8), c.getString(9), c.getString(10));
    }

    private static long scalarLong(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor c = db.rawQuery(sql, args)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    public static long startOfDay(long timeMs) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(timeMs);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static final class TimelineEntry {
        @NonNull public final String eventKey;
        public final long startMs;
        public final long endMs;
        @NonNull public final String kind;
        @Nullable public final String packageName;
        @Nullable public final String appLabel;
        public final long durationMs;
        public final boolean systemApp;
        @Nullable public final String detail;
        @Nullable public final String source;
        @Nullable public final String sourceUri;

        public TimelineEntry(@NonNull String eventKey, long startMs, long endMs,
                             @NonNull String kind, @Nullable String packageName,
                             @Nullable String appLabel, long durationMs, boolean systemApp,
                             @Nullable String detail, @Nullable String source,
                             @Nullable String sourceUri) {
            this.eventKey = eventKey;
            this.startMs = startMs;
            this.endMs = endMs;
            this.kind = kind;
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.durationMs = durationMs;
            this.systemApp = systemApp;
            this.detail = detail;
            this.source = source;
            this.sourceUri = sourceUri;
        }
    }

    public static final class PackageState {
        @NonNull public final String packageName;
        @Nullable public final String appLabel;
        public final boolean systemApp;
        public final long firstInstallMs;
        public final long lastUpdateMs;
        @Nullable public final String source;
        @Nullable public final String sourceUri;

        public PackageState(@NonNull String packageName, @Nullable String appLabel,
                            boolean systemApp, long firstInstallMs, long lastUpdateMs,
                            @Nullable String source, @Nullable String sourceUri) {
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.systemApp = systemApp;
            this.firstInstallMs = firstInstallMs;
            this.lastUpdateMs = lastUpdateMs;
            this.source = source;
            this.sourceUri = sourceUri;
        }
    }

    public static final class Summary {
        public final long appUsageMs;
        public final long screenOnMs;
        public final long screenOffMs;
        public final int appsUsed;

        Summary(long appUsageMs, long screenOnMs, long screenOffMs, int appsUsed) {
            this.appUsageMs = appUsageMs;
            this.screenOnMs = screenOnMs;
            this.screenOffMs = screenOffMs;
            this.appsUsed = appsUsed;
        }
    }
}
