package fr.neamar.kiss.battery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.PowerManager;

public final class BatteryHistoryStore extends SQLiteOpenHelper {
    private static final String DB = "smart_battery.db";
    private static final int VERSION = 2;
    private static final long KEEP_MS = 1000L * 60L * 60L * 24L * 120L;
    private final Context context;

    public BatteryHistoryStore(Context context) {
        super(context.getApplicationContext(), DB, null, VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE samples(ts INTEGER PRIMARY KEY, level INTEGER, charging INTEGER, screen_on INTEGER DEFAULT 1, temp REAL, voltage INTEGER, current_ua INTEGER, avg_ua INTEGER, charge_uah INTEGER, energy_nwh INTEGER)");
        db.execSQL("CREATE INDEX idx_samples_level ON samples(level)");
        db.execSQL("CREATE INDEX idx_samples_ts ON samples(ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE samples ADD COLUMN screen_on INTEGER DEFAULT 1");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_samples_ts ON samples(ts)");
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
                "SELECT level,charge_uah FROM samples WHERE charging=1 AND charge_uah IS NOT NULL ORDER BY ts DESC LIMIT 300",
                null);
        int[] levels = new int[300];
        long[] charges = new long[300];
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
        double sum = 0;
        int count = 0;
        for (int newer = 0; newer < n; newer++) {
            for (int older = newer + 1; older < n; older++) {
                int dp = levels[newer] - levels[older];
                long dq = charges[newer] - charges[older];
                if (dp >= 15 && dq > 0) {
                    double full = dq * 100.0 / dp;
                    if (full > 300_000 && full < 20_000_000) {
                        sum += full;
                        count++;
                        break;
                    }
                }
            }
            if (count >= 24) break;
        }
        return count == 0 ? -1 : Math.round(sum / count);
    }

    public double averageTemperature24h() { return average("AVG(temp)", "1=1"); }
    public double averageDrainMa24h() { return average("AVG(ABS(current_ua))/1000.0", "charging=0 AND current_ua IS NOT NULL"); }
    public double averageScreenOnDrainMa24h() { return average("AVG(ABS(current_ua))/1000.0", "charging=0 AND screen_on=1 AND current_ua IS NOT NULL"); }
    public double averageScreenOffDrainMa24h() { return average("AVG(ABS(current_ua))/1000.0", "charging=0 AND screen_on=0 AND current_ua IS NOT NULL"); }
    public double averageChargeMa24h() { return average("AVG(ABS(current_ua))/1000.0", "charging=1 AND current_ua IS NOT NULL"); }

    private double average(String expression, String where) {
        Cursor c = getReadableDatabase().rawQuery("SELECT " + expression + " FROM samples WHERE " + where + " AND ts>?",
                new String[]{Long.toString(System.currentTimeMillis() - 86_400_000L)});
        try { return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : Double.NaN; }
        finally { c.close(); }
    }

    public int sampleCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM samples", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }
}
