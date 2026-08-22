package fr.neamar.kiss.index;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import fr.neamar.kiss.pojo.CommunicationPojo;

public final class CommunicationIndexStore extends SQLiteOpenHelper {
    private static final String DB = "smart_index.db";
    private static final int VERSION = 1;

    public static final class Stats {
        public final int total;
        public final int calls;
        public final int sms;
        public final int truecaller;
        public final long newest;
        Stats(int total, int calls, int sms, int truecaller, long newest) {
            this.total = total; this.calls = calls; this.sms = sms; this.truecaller = truecaller; this.newest = newest;
        }
    }

    public CommunicationIndexStore(Context context) { super(context.getApplicationContext(), DB, null, VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE communication_index(_id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, source_id TEXT NOT NULL, package_name TEXT, address TEXT, display_name TEXT, body TEXT, event_time INTEGER, notification_id TEXT, UNIQUE(source,source_id) ON CONFLICT REPLACE)");
        db.execSQL("CREATE INDEX idx_comm_time ON communication_index(event_time DESC)");
        db.execSQL("CREATE INDEX idx_comm_source ON communication_index(source)");
        db.execSQL("CREATE INDEX idx_comm_address ON communication_index(address)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public void clear() { getWritableDatabase().delete("communication_index", null, null); }

    public void clearSource(String source) {
        getWritableDatabase().delete("communication_index", "source=?", new String[]{source});
    }

    public void put(String source, String sourceId, String packageName, String address,
                    String displayName, String body, long eventTime, String notificationId) {
        ContentValues v = new ContentValues();
        v.put("source", source); v.put("source_id", sourceId); v.put("package_name", packageName);
        v.put("address", address); v.put("display_name", displayName); v.put("body", body);
        v.put("event_time", eventTime); v.put("notification_id", notificationId);
        getWritableDatabase().insertWithOnConflict("communication_index", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void trimOlderThan(long cutoff) {
        if (cutoff > 0) getWritableDatabase().delete("communication_index", "event_time<?", new String[]{Long.toString(cutoff)});
    }

    public List<CommunicationPojo> search(String query, int limit) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) return new ArrayList<>();
        String like = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%";
        List<CommunicationPojo> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("communication_index",
                new String[]{"_id","source","package_name","address","display_name","body","event_time","notification_id"},
                "display_name LIKE ? ESCAPE '\\' OR address LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\'",
                new String[]{like, like, like}, null, null, "event_time DESC", Integer.toString(Math.max(1, limit)))) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String source = c.getString(1);
                CommunicationPojo.Kind kind = "call".equals(source) ? CommunicationPojo.Kind.CALL
                        : "sms".equals(source) ? CommunicationPojo.Kind.SMS
                        : CommunicationPojo.Kind.TRUECALLER_NOTIFICATION;
                CommunicationPojo p = new CommunicationPojo("communication://" + id, kind,
                        c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6), c.getString(7));
                p.relevance = 24;
                out.add(p);
            }
        }
        return out;
    }

    public CommunicationPojo find(long id) {
        try (Cursor c = getReadableDatabase().query("communication_index",
                new String[]{"source","package_name","address","display_name","body","event_time","notification_id"},
                "_id=?", new String[]{Long.toString(id)}, null, null, null)) {
            if (!c.moveToFirst()) return null;
            String source = c.getString(0);
            CommunicationPojo.Kind kind = "call".equals(source) ? CommunicationPojo.Kind.CALL
                    : "sms".equals(source) ? CommunicationPojo.Kind.SMS
                    : CommunicationPojo.Kind.TRUECALLER_NOTIFICATION;
            return new CommunicationPojo("communication://" + id, kind, c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getString(6));
        }
    }

    public Stats stats() {
        int total=0,calls=0,sms=0,truecaller=0; long newest=0;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT source,COUNT(*),MAX(event_time) FROM communication_index GROUP BY source", null)) {
            while (c.moveToNext()) {
                String s=c.getString(0); int n=c.getInt(1); total+=n; newest=Math.max(newest,c.getLong(2));
                if ("call".equals(s)) calls=n; else if ("sms".equals(s)) sms=n; else truecaller=n;
            }
        }
        return new Stats(total,calls,sms,truecaller,newest);
    }
}
