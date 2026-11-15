package fr.neamar.kiss.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Persistent recall must be bounded separately from actual app-launch History. */
public final class SearchRecallDatabase195RegressionTest {
    @Test public void recordedSearchesSurviveReopenAndPruneWithoutFakeLaunches() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DBHelper.clearHistory(app);
        try {
            DBHelper.replaceSearchRecall(app, "whatsapp", Arrays.asList(
                    "app://first195", "app://second195"), 3);
            assertEquals(0, DBHelper.getHistoryLength(app));
            DBHelper.replaceSearchRecall(app, "telegram", Arrays.asList(
                    "app://third195", "app://fourth195"), 3);
            List<DBHelper.SearchRecallRow> saved = DBHelper.getRecentSearchRecall(app, 3);
            assertEquals(3, saved.size());
            assertEquals("app://second195", saved.get(0).record);
            assertEquals("telegram", saved.get(1).query);
            assertEquals("app://fourth195", saved.get(2).record);

            DBHelper.replaceSearchRecall(app, "whatsapp", Arrays.asList(
                    "app://second195", "app://new195", "app://new195"), 3);
            saved = DBHelper.getRecentSearchRecall(app, 50);
            assertEquals(3, saved.size());
            assertEquals("app://fourth195", saved.get(0).record);
            assertEquals("app://second195", saved.get(1).record);
            assertEquals("app://new195", saved.get(2).record);
            assertEquals(0, DBHelper.getHistoryLength(app));
        } finally {
            DBHelper.clearHistory(app);
        }
        assertTrue(DBHelper.getRecentSearchRecall(app, 10).isEmpty());
    }

    @Test public void legacyDatabaseUpgradeAddsRecallWithoutLosingLaunchHistory() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (SQLiteDatabase db = SQLiteDatabase.create(null); DB helper = new DB(app)) {
            helper.onCreate(db);
            db.execSQL("INSERT INTO history(record,timeStamp) VALUES('app://preserve195',195)");
            db.execSQL("DROP TABLE search_recall");
            helper.onUpgrade(db, 21, 22);
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM history", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(1, cursor.getInt(0));
            }
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM search_recall", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(0, cursor.getInt(0));
            }
        }
    }
}
