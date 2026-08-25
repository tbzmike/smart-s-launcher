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
    private static final int CAPACITY_SAMPLE_LIMIT = 1000;
    private static final int MIN_CAPACITY_LEVEL_SPAN = 5;
    private static final long MAX_CAPACITY_SAMPLE_GAP_MS = 45L * 60_000L;
    private static final String LEARNING_PREFS = "smart_battery_learning";
    private static final String PREF_OBSERVED_CAPACITY_UAH = "observed_capacity_uah";
    private final Context context;

    public static final class SamplePoint {
        public final long ts;
        public final int level;
        public final boolean charging;
        public final boolean screenOn;
        public final float temp;
        public final long currentUa;
        public final long chargeUah;

        SamplePoint(long ts, int level, boolean charging, boolean screenOn, float temp,
                    long currentUa, long chargeUah) {
            this.ts = ts;
            this.level = level;
            this.charging = charging;
            this.screenOn = screenOn;
            this.temp = temp;
            this.currentUa = currentUa;
            this.chargeUah = chargeUah;
        }
    }

    public static final class CurrentSessionStats {
        public final boolean charging;
        public final long durationMs;
        public final double averageCurrentMa;
        public final double percentPerHour;
        public final double totalMah;
        public final double screenOnCurrentMa;
        public final double screenOnPercentPerHour;
        public final double screenOffCurrentMa;
        public final double screenOffPercentPerHour;
        public final long estimatedRemainingMs;

        CurrentSessionStats(boolean charging, long durationMs, double averageCurrentMa,
                            double percentPerHour, double totalMah,
                            double screenOnCurrentMa, double screenOnPercentPerHour,
                            double screenOffCurrentMa, double screenOffPercentPerHour,
                            long estimatedRemainingMs) {
            this.charging = charging;
            this.durationMs = durationMs;
            this.averageCurrentMa = averageCurrentMa;
            this.percentPerHour = percentPerHour;
            this.totalMah = totalMah;
            this.screenOnCurrentMa = screenOnCurrentMa;
            this.screenOnPercentPerHour = screenOnPercentPerHour;
            this.screenOffCurrentMa = screenOffCurrentMa;
            this.screenOffPercentPerHour = screenOffPercentPerHour;
            this.estimatedRemainingMs = estimatedRemainingMs;
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
        if (s.percent() < 0) return;
        ContentValues v = new ContentValues();
        v.put("ts", s.timestamp);
        v.put("level", s.percent());
        v.put("charging", s.isCharging() ? 1 : 0);
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        v.put("screen_on", pm == null || pm.isInteractive() ? 1 : 0);
        v.put("temp", s.temperatureC);
        v.put("voltage", s.voltageMv);
        long sampledCurrentUa = s.sampleCurrentUa();
        if (sampledCurrentUa != Long.MIN_VALUE) v.put("current_ua", sampledCurrentUa);
        if (s.averageCurrentUa != Long.MIN_VALUE) v.put("avg_ua", s.averageCurrentUa);
        if (s.chargeCounterUah != Long.MIN_VALUE) v.put("charge_uah", s.chargeCounterUah);
        if (s.energyNwh != Long.MIN_VALUE) v.put("energy_nwh", s.energyNwh);
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("samples", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        db.delete("samples", "ts < ?", new String[]{Long.toString(System.currentTimeMillis() - KEEP_MS)});
    }

    public long estimatedFullCapacityUah() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts,level,charging,charge_uah FROM samples "
                        + "WHERE charge_uah IS NOT NULL ORDER BY ts DESC LIMIT ?",
                new String[]{Integer.toString(CAPACITY_SAMPLE_LIMIT)});
        List<SamplePoint> newestFirst = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                newestFirst.add(new SamplePoint(c.getLong(0), c.getInt(1), c.getInt(2) != 0,
                        true, Float.NaN, Long.MIN_VALUE, c.getLong(3)));
            }
        } finally {
            c.close();
        }

        List<SamplePoint> chronological = new ArrayList<>(newestFirst.size());
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            chronological.add(newestFirst.get(i));
        }
        long observed = estimateFullCapacityUah(chronological);
        android.content.SharedPreferences learning = context.getSharedPreferences(
                LEARNING_PREFS, Context.MODE_PRIVATE);
        if (BatteryCapacityEstimator.isValidFullCapacityUah(observed)) {
            learning.edit().putLong(PREF_OBSERVED_CAPACITY_UAH, observed).apply();
            return observed;
        }

        long saved = learning.getLong(PREF_OBSERVED_CAPACITY_UAH, -1L);
        return BatteryCapacityEstimator.isValidFullCapacityUah(saved) ? saved : -1L;
    }

    static long estimateFullCapacityUah(List<SamplePoint> points) {
        if (points == null || points.size() < 2) return -1L;
        double weighted = 0.0;
        double weights = 0.0;
        int anchor = 0;

        for (int i = 1; i < points.size(); i++) {
            SamplePoint previous = points.get(i - 1);
            SamplePoint next = points.get(i);
            if (next.charging != previous.charging
                    || next.ts <= previous.ts
                    || next.ts - previous.ts > MAX_CAPACITY_SAMPLE_GAP_MS
                    || next.chargeUah == Long.MIN_VALUE) {
                anchor = i;
                continue;
            }

            SamplePoint first = points.get(anchor);
            if (first.charging != next.charging || first.chargeUah == Long.MIN_VALUE) {
                anchor = i;
                continue;
            }

            int levelDelta = next.level - first.level;
            long chargeDelta = next.chargeUah - first.chargeUah;
            int levelSpan = Math.abs(levelDelta);
            if (levelSpan < MIN_CAPACITY_LEVEL_SPAN) continue;

            boolean correctDirection = next.charging
                    ? levelDelta > 0 && chargeDelta > 0
                    : levelDelta < 0 && chargeDelta < 0;
            if (!correctDirection) {
                anchor = i;
                continue;
            }

            long full = Math.round(Math.abs(chargeDelta) * 100.0 / levelSpan);
            if (BatteryCapacityEstimator.isValidFullCapacityUah(full)) {
                double weight = Math.min(2.0, levelSpan / 10.0);
                weighted += full * weight;
                weights += weight;
            }
            anchor = i;
        }
        return weights <= 0.0 ? -1L : Math.round(weighted / weights);
    }

    public List<SamplePoint> recentSamples(long windowMs, int max) {
        List<SamplePoint> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts,level,charging,screen_on,temp,current_ua,charge_uah FROM ("
                        + "SELECT ts,level,charging,screen_on,temp,current_ua,charge_uah "
                        + "FROM samples WHERE ts>? ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC",
                new String[]{Long.toString(System.currentTimeMillis() - windowMs), Integer.toString(Math.max(1, max))});
        try {
            while (c.moveToNext()) {
                out.add(new SamplePoint(c.getLong(0), c.getInt(1), c.getInt(2) != 0, c.getInt(3) != 0,
                        c.isNull(4) ? Float.NaN : c.getFloat(4),
                        c.isNull(5) ? Long.MIN_VALUE : c.getLong(5),
                        c.isNull(6) ? Long.MIN_VALUE : c.getLong(6)));
            }
        } finally { c.close(); }
        return out;
    }

    public CurrentSessionStats currentSessionStats(BatterySnapshot current) {
        List<SamplePoint> points = recentSamples(48L * 60L * 60L * 1000L, 1500);
        if (current.percent() >= 0
                && (points.isEmpty() || current.timestamp > points.get(points.size() - 1).ts)) {
            points.add(new SamplePoint(current.timestamp, current.percent(), current.isCharging(),
                    isScreenOn(), current.temperatureC, current.sampleCurrentUa(),
                    current.chargeCounterUah));
        }
        if (points.isEmpty()) return emptySession(current);

        int start = points.size() - 1;
        while (start > 0 && points.get(start - 1).charging == current.isCharging()) start--;
        SamplePoint first = points.get(start);
        long endMs = Math.max(current.timestamp, points.get(points.size() - 1).ts);
        long durationMs = Math.max(0L, endMs - first.ts);
        double hours = durationMs / 3_600_000.0;

        double totalCurrent = 0.0;
        int currentCount = 0;
        double onCurrent = 0.0;
        int onCount = 0;
        double offCurrent = 0.0;
        int offCount = 0;
        SamplePoint lastWithCharge = null;
        SamplePoint firstWithCharge = null;

        for (int i = start; i < points.size(); i++) {
            SamplePoint p = points.get(i);
            if (p.charging != current.isCharging()) continue;
            if (p.currentUa != Long.MIN_VALUE) {
                double ma = Math.abs(p.currentUa) / 1000.0;
                totalCurrent += ma;
                currentCount++;
                if (p.screenOn) {
                    onCurrent += ma;
                    onCount++;
                } else {
                    offCurrent += ma;
                    offCount++;
                }
            }
            if (p.chargeUah != Long.MIN_VALUE) {
                if (firstWithCharge == null) firstWithCharge = p;
                lastWithCharge = p;
            }
        }

        double avgAbs = currentCount == 0 ? Double.NaN : totalCurrent / currentCount;
        double avgSigned = Double.isNaN(avgAbs) ? Double.NaN : (current.isCharging() ? avgAbs : -avgAbs);
        double onAbs = onCount == 0 ? Double.NaN : onCurrent / onCount;
        double offAbs = offCount == 0 ? Double.NaN : offCurrent / offCount;
        double onSigned = Double.isNaN(onAbs) ? Double.NaN : (current.isCharging() ? onAbs : -onAbs);
        double offSigned = Double.isNaN(offAbs) ? Double.NaN : (current.isCharging() ? offAbs : -offAbs);

        int deltaPercent = current.percent() < 0 ? 0 : current.percent() - first.level;
        double percentPerHour = hours >= (5.0 / 60.0) ? deltaPercent / hours : Double.NaN;
        long observedCapacityUah = estimatedFullCapacityUah();
        long capacityUah = BatteryCapacityEstimator.resolve(context, observedCapacityUah, current)
                .fullCapacityUah;

        double totalMah = Double.NaN;
        if (firstWithCharge != null && lastWithCharge != null && firstWithCharge != lastWithCharge) {
            long deltaUah = Math.abs(lastWithCharge.chargeUah - firstWithCharge.chargeUah);
            if (deltaUah > 0) totalMah = deltaUah / 1000.0;
        }
        if (Double.isNaN(totalMah) && capacityUah > 0) {
            totalMah = Math.abs(deltaPercent) * capacityUah / 100_000.0;
        }

        double onPercent = currentEquivalentPercentRate(onAbs, capacityUah, percentPerHour, avgAbs);
        double offPercent = currentEquivalentPercentRate(offAbs, capacityUah, percentPerHour, avgAbs);
        if (!current.isCharging()) {
            if (!Double.isNaN(onPercent)) onPercent = -Math.abs(onPercent);
            if (!Double.isNaN(offPercent)) offPercent = -Math.abs(offPercent);
        }

        long remainingMs = Long.MIN_VALUE;
        if (current.isCharging()) {
            remainingMs = current.chargeTimeRemainingMs;
        } else if (current.percent() >= 0 && !Double.isNaN(percentPerHour) && percentPerHour < -0.1) {
            remainingMs = Math.round((current.percent() / Math.abs(percentPerHour)) * 3_600_000.0);
        } else if (current.percent() >= 0 && capacityUah > 0
                && !Double.isNaN(avgAbs) && avgAbs > 1.0) {
            double remainingMah = (capacityUah / 1000.0) * current.percent() / 100.0;
            remainingMs = Math.round((remainingMah / avgAbs) * 3_600_000.0);
        }

        return new CurrentSessionStats(current.isCharging(), durationMs, avgSigned,
                percentPerHour, totalMah, onSigned, onPercent, offSigned, offPercent, remainingMs);
    }

    private CurrentSessionStats emptySession(BatterySnapshot current) {
        double now = current.sampleCurrentMa();
        double signed = Double.isNaN(now) ? Double.NaN
                : (current.isCharging() ? Math.abs(now) : -Math.abs(now));
        boolean screenOn = isScreenOn();
        return new CurrentSessionStats(current.isCharging(), 0L, signed, Double.NaN,
                Double.NaN, screenOn ? signed : Double.NaN, Double.NaN,
                screenOn ? Double.NaN : signed, Double.NaN,
                current.isCharging() ? current.chargeTimeRemainingMs : Long.MIN_VALUE);
    }

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private static double currentEquivalentPercentRate(double currentMa, long capacityUah,
                                                       double overallPercentRate, double averageMa) {
        if (Double.isNaN(currentMa)) return Double.NaN;
        if (capacityUah > 0) return currentMa / (capacityUah / 1000.0) * 100.0;
        if (!Double.isNaN(overallPercentRate) && !Double.isNaN(averageMa) && averageMa > 1.0) {
            return Math.abs(overallPercentRate) * currentMa / averageMa;
        }
        return Double.NaN;
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
        if (capacity <= 0) {
            int designMah = BatteryCapacityEstimator.designCapacityMah(context);
            if (designMah > 0) capacity = designMah * 1000L;
        }
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
