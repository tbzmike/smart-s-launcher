package fr.neamar.kiss.battery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public final class BatteryHistoryStore extends SQLiteOpenHelper {
    private static final String DB = "smart_battery.db";
    private static final int VERSION = 1;
    private static final long KEEP_MS = 1000L * 60L * 60L * 24L * 120L;

    public BatteryHistoryStore(Context context) {
        super(context.getApplicationContext(), DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE samples(ts INTEGER PRIMARY KEY, level INTEGER, charging INTEGER, temp REAL, voltage INTEGER, current_ua INTEGER, avg_ua INTEGER, charge_uah INTEGER, energy_nwh INTEGER)");
        db.execSQL("CREATE INDEX idx_samples_level ON samples(level)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public void add(BatterySnapshot s) {
        ContentValues v = new ContentValues();
        v.put("ts", s.timestamp);
        v.put("level", s.percent());
        v.put("charging", s.isCharging() ? 1 : 0);
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
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT a.level,a.charge_uah,b.level,b.charge_uah FROM samples a JOIN samples b ON b.ts>a.ts WHERE a.charging=1 AND b.charging=1 AND a.charge_uah IS NOT NULL AND b.charge_uah IS NOT NULL AND (b.level-a.level)>=15 ORDER BY (b.ts-a.ts) ASC LIMIT 24";
        Cursor c = db.rawQuery(sql, null);
        double sum = 0;
        int count = 0;
        try {
            while (c.moveToNext()) {
                int p1 = c.getInt(0);
                long q1 = c.getLong(1);
                int p2 = c.getInt(2);
                long q2 = c.getLong(3);
                int dp = p2 - p1;
                long dq = q2 - q1;
                if (dp >= 15 && dq > 0) {
                    double full = dq * 100.0 / dp;
                    if (full > 300_000 && full < 20_000_000) {
                        sum += full;
                        count++;
                    }
                }
            }
        } finally {
            c.close();
        }
        return count == 0 ? -1 : Math.round(sum / count);
    }

    public double averageTemperature24h() {
        Cursor c = getReadableDatabase().rawQuery("SELECT AVG(temp) FROM samples WHERE ts>?", new String[]{Long.toString(System.currentTimeMillis()-86_400_000L)});
        try { return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : Double.NaN; }
        finally { c.close(); }
    }

    public double averageDrainMa24h() {
        Cursor c = getReadableDatabase().rawQuery("SELECT AVG(ABS(current_ua))/1000.0 FROM samples WHERE charging=0 AND current_ua IS NOT NULL AND ts>?", new String[]{Long.toString(System.currentTimeMillis()-86_400_000L)});
        try { return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : Double.NaN; }
        finally { c.close(); }
    }

    public int sampleCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM samples", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }
}
