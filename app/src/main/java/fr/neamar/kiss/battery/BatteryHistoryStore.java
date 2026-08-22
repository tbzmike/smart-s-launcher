package fr.neamar.kiss.battery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

public final class BatteryHistoryStore extends SQLiteOpenHelper {
    private static final String DB = "smart_battery.db";
    private static final int VERSION = 3;
    private static final long KEEP_MS = 1000L * 60L * 60L * 24L * 180L;
    private final Context context;

    public static final class SamplePoint {
        public final long ts;
        public final int level;
        public final boolean charging;
        public final boolean screenOn;
        public final float temp;
        public final long currentUa;

        SamplePoint(long ts, int level, boolean charging, boolean screenOn, float temp, long currentUa) {
            this.ts = ts;
            this.level = level;
            this.charging = charging;
            this.screenOn = screenOn;
            this.temp = temp;
            this.currentUa = currentUa;
        }
    }

    public static final class SessionSummary {
        public final boolean charging;
        public final long startMs;
        public final long endMs;
        public final int startLevel;
        public final int endLevel;
        public final double averageCurrentMa;
        public final float maxTemperatureC;
        public final long deliveredUah;

        SessionSummary(boolean charging, long startMs, long endMs, int startLevel, int endLevel,
                       double averageCurrentMa, float maxTemperatureC, long deliveredUah) {
            this.charging = charging;
            this.startMs = startMs;
            this.endMs = endMs;
            this.startLevel = startLevel;
            this.endLevel = endLevel;
            this.averageCurrentMa = averageCurrentMa;
            this.maxTemperatureC = maxTemperatureC;
            this.deliveredUah = deliveredUah;
        }

        public long durationMs() { return Math.max(0L, endMs - startMs); }
    }

    public BatteryHistoryStore(Context context) {
        super(context.getApplicationContext(), DB, null, VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE samples(ts INTEGER PRIMARY KEY, level INTEGER, charging INTEGER, screen_on INTEGER DEFAULT 1, temp REAL, voltage INTEGER, current_ua INTEGER, avg_ua INTEGER, charge_uah INTEGER, energy_nwh INTEGER)");
        db.execSQL("CREATE INDEX idx_samples_level ON samples(level)");
        db.execSQL("CREATE INDEX idx_samples_ts ON samples(ts)");
        db.execSQL("CREATE INDEX idx_samples_charge_ts ON samples(charging,ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE samples ADD COLUMN screen_on INTEGER DEFAULT 1");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_samples_ts ON samples(ts)");
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_samples_charge_ts ON samples(charging,ts)");
        }
    }

    public void add(BatterySnapshot s) {
        ContentValues v = new ContentValues();
        v.put("ts", s.timestamp);
        v.put("level", s.percent());
        v.put("charging", s.isCharging() ? 1 : 0);
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        v.put("screen_on", pm == null || pm.isInteractive() ? 1 : 0);
        v.put("temp", s.temperatureC);
        v.put("voltage", s.voltageMv);
        if (s.currentUa != Long.MIN_VALUE) v.put("current_ua", s.currentUa);
        if (s.averageCurrentUa != Long.MIN_VALUE) v.put("avg_ua", s.averageCurrentUa);
        if (s.chargeCounterUah != Long.MIN_VALUE) v.put("charge_uah", s.chargeCounterUah);
        if (s.energyNwh != Long.MIN_VALUE) v.put("energy_nwh", s.energyNwh);
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("samples", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        db.delete("samples", "ts < ?", new String[]{Long.toString(System.currentTimeMillis() - KEEP_MS)});
    }

    public long estimatedFullCapacityUah() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT level,charge_uah FROM samples WHERE charging=1 AND charge_uah IS NOT NULL ORDER BY ts DESC LIMIT 500",
                null);
        int[] levels = new int[500];
        long[] charges = new long[500];
        int n = 0;
        try {
            while (c.moveToNext() && n < levels.length) {
                levels[n] = c.getInt(0);
                charges[n] = c.getLong(1);
                n++;
            }
        } finally {
            c.close();
        }
        double weighted = 0;
        double weights = 0;
        int count = 0;
        for (int newer = 0; newer < n; newer++) {
            for (int older = newer + 1; older < n; older++) {
                int dp = levels[newer] - levels[older];
                long dq = charges[newer] - charges[older];
                if (dp >= 15 && dq > 0) {
                    double full = dq * 100.0 / dp;
                    if (full > 300_000 && full < 20_000_000) {
                        double weight = Math.min(1.0, dp / 40.0);
                        weighted += full * weight;
                        weights += weight;
                        count++;
                        break;
                    }
                }
            }
            if (count >= 40) break;
        }
        return weights == 0 ? -1 : Math.round(weighted / weights);
    }

    public List<SamplePoint> recentSamples(long windowMs, int max) {
        List<SamplePoint> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts,level,charging,screen_on,temp,current_ua FROM samples WHERE ts>? ORDER BY ts ASC LIMIT ?",
                new String[]{Long.toString(System.currentTimeMillis() - windowMs), Integer.toString(Math.max(1, max))});
        try {
            while (c.moveToNext()) {
                out.add(new SamplePoint(c.getLong(0), c.getInt(1), c.getInt(2) != 0, c.getInt(3) != 0,
                        c.isNull(4) ? Float.NaN : c.getFloat(4), c.isNull(5) ? Long.MIN_VALUE : c.getLong(5)));
            }
        } finally { c.close(); }
        return out;
    }

    public List<SessionSummary> recentSessions(int maxSessions) {
        List<SamplePoint> points = recentSamples(KEEP_MS, 2500);
        List<SessionSummary> sessions = new ArrayList<>();
        if (points.size() < 2) return sessions;
        int start = 0;
        for (int i = 1; i <= points.size(); i++) {
            boolean end = i == points.size() || points.get(i).charging != points.get(start).charging;
            if (!end) continue;
            SamplePoint first = points.get(start);
            SamplePoint last = points.get(i - 1);
            if (last.ts - first.ts >= 10 * 60_000L) {
                double currentSum = 0;
                int currentCount = 0;
                float maxTemp = Float.NaN;
                for (int j = start; j < i; j++) {
                    SamplePoint p = points.get(j);
                    if (p.currentUa != Long.MIN_VALUE) {
                        currentSum += Math.abs(p.currentUa) / 1000.0;
                        currentCount++;
                    }
                    if (!Float.isNaN(p.temp) && (Float.isNaN(maxTemp) || p.temp > maxTemp)) maxTemp = p.temp;
                }
                long delivered = estimateDeliveredUah(first, last);
                sessions.add(new SessionSummary(first.charging, first.ts, last.ts, first.level, last.level,
                        currentCount == 0 ? Double.NaN : currentSum / currentCount, maxTemp, delivered));
                if (sessions.size() > maxSessions * 2) sessions.remove(0);
            }
            start = i;
        }
        int from = Math.max(0, sessions.size() - Math.max(1, maxSessions));
        return new ArrayList<>(sessions.subList(from, sessions.size()));
    }

    private long estimateDeliveredUah(SamplePoint first, SamplePoint last) {
        long capacity = estimatedFullCapacityUah();
        if (capacity <= 0) return -1;
        int delta = Math.abs(last.level - first.level);
        return Math.round(capacity * (delta / 100.0));
    }

    public double equivalentChargeCycles(long windowMs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT level,charging FROM samples WHERE ts>? ORDER BY ts ASC",
                new String[]{Long.toString(System.currentTimeMillis() - windowMs)});
        int previousLevel = -1;
        boolean previousCharging = false;
        double chargedPercent = 0;
        try {
            while (c.moveToNext()) {
                int level = c.getInt(0);
                boolean charging = c.getInt(1) != 0;
                if (previousLevel >= 0 && charging && previousCharging && level > previousLevel) {
                    chargedPercent += level - previousLevel;
                }
                previousLevel = level;
                previousCharging = charging;
            }
        } finally { c.close(); }
        return chargedPercent / 100.0;
    }

    public double highSocChargePercent(long windowMs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT level,charging FROM samples WHERE ts>? ORDER BY ts ASC",
                new String[]{Long.toString(System.currentTimeMillis() - windowMs)});
        int previousLevel = -1;
        boolean previousCharging = false;
        double high = 0;
        try {
            while (c.moveToNext()) {
                int level = c.getInt(0);
                boolean charging = c.getInt(1) != 0;
                if (previousLevel >= 0 && charging && previousCharging && level > previousLevel && level > 80) {
                    high += level - Math.max(previousLevel, 80);
                }
                previousLevel = level;
                previousCharging = charging;
            }
        } finally { c.close(); }
        return high;
    }

    public double averageTemperature24h() { return average("AVG(temp)", "1=1", 86_400_000L); }
    public double averageDrainMa24h() { return average("AVG(ABS(current_ua))/1000.0", "charging=0 AND current_ua IS NOT NULL", 86_400_000L); }
    public double averageScreenOnDrainMa24h() { return average("AVG(ABS(current_ua))/1000.0", "charging=0 AND screen_on=1 AND current_ua IS NOT NULL", 86_400_000L); }
    public double averageScreenOffDrainMa24h() { return average("AVG(ABS(current_ua))/1000.0", "charging=0 AND screen_on=0 AND current_ua IS NOT NULL", 86_400_000L); }
    public double averageChargeMa24h() { return average("AVG(ABS(current_ua))/1000.0", "charging=1 AND current_ua IS NOT NULL", 86_400_000L); }
    public double averageDrainMa7d() { return average("AVG(ABS(current_ua))/1000.0", "charging=0 AND current_ua IS NOT NULL", 7L * 86_400_000L); }

    private double average(String expression, String where, long windowMs) {
        Cursor c = getReadableDatabase().rawQuery("SELECT " + expression + " FROM samples WHERE " + where + " AND ts>?",
                new String[]{Long.toString(System.currentTimeMillis() - windowMs)});
        try { return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : Double.NaN; }
        finally { c.close(); }
    }

    public int sampleCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM samples", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }
}
