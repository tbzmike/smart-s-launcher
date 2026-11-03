package fr.neamar.kiss.db;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public final class NotificationDatabase188RegressionTest {
    @Test public void upgradeRetainsRowsAndAddsIndexesForReceiptAndSavedHistoryLookups() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (SQLiteDatabase db = SQLiteDatabase.create(null); DB helper = new DB(app)) {
            helper.onCreate(db);
            db.execSQL("INSERT INTO history(record,timeStamp) VALUES('notification://upgrade188',188)");
            db.execSQL("INSERT INTO notification_history(notification_id,package,app_name,post_time)"
                    + " VALUES('notification://upgrade188','test.upgrade188','Upgrade188',188)");
            db.execSQL("DROP INDEX idx_history_record_time");
            db.execSQL("DROP INDEX idx_notification_history_identity");
            helper.onUpgrade(db, 20, 21);
            assertEquals(1L, count(db, "history"));
            assertEquals(1L, count(db, "notification_history"));
            assertIndex(db, "SELECT _id FROM history WHERE record='notification://upgrade188' AND timeStamp>=188",
                    "idx_history_record_time");
            assertIndex(db, "SELECT * FROM notification_history WHERE notification_id='notification://upgrade188'"
                    + " ORDER BY post_time DESC,_id DESC LIMIT 1", "idx_notification_history_identity");
        }
    }

    @Test public void launchHousekeepingCannotDeleteOldSavedNotificationLinks() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (SQLiteDatabase db = SQLiteDatabase.create(null); DB helper = new DB(app)) {
            helper.onCreate(db);
            db.execSQL("INSERT INTO history(record,timeStamp) VALUES('notification://keep188',1)");
            db.execSQL("INSERT INTO history(record,timeStamp) VALUES('app://old188',1)");
            db.execSQL("INSERT INTO history(record,timeStamp) VALUES('app://recent188',1000)");
            DBHelper.pruneOldLaunchHistory(db, 100L);
            assertEquals(2L, count(db, "history"));
            try (Cursor c = db.rawQuery("SELECT record FROM history ORDER BY _id", null)) {
                assertTrue(c.moveToFirst());
                assertEquals("notification://keep188", c.getString(0));
                assertTrue(c.moveToNext());
                assertEquals("app://recent188", c.getString(0));
            }
        }
    }

    private static long count(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            assertTrue(c.moveToFirst()); return c.getLong(0);
        }
    }

    private static void assertIndex(SQLiteDatabase db, String sql, String index) {
        StringBuilder plan = new StringBuilder();
        try (Cursor c = db.rawQuery("EXPLAIN QUERY PLAN " + sql, null)) {
            while (c.moveToNext()) plan.append(c.getString(3));
        }
        assertTrue(plan.toString(), plan.toString().contains(index));
    }
}
