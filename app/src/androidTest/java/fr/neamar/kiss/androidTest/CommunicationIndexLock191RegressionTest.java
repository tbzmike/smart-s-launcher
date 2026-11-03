package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.dataprovider.simpleprovider.SearchProvider;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.index.CommunicationIndexer;
import fr.neamar.kiss.index.CommunicationIndexStore;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

/** Reproduces the reported SQLITE_BUSY using a real separate SQLite write connection. */
public final class CommunicationIndexLock191RegressionTest {
    private static final String SOURCE = "call";
    private static final String SOURCE_ID = "locked-history-191";
    private static final String ID = CommunicationIndexStore.stableId(SOURCE, SOURCE_ID);

    @Test public void exclusiveDatabaseLockCannotCrashLookupAndDataReturnsAfterUnlock() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        boolean oldAuto = prefs.getBoolean(CommunicationIndexer.PREF_AUTO, true);
        prefs.edit().putBoolean(CommunicationIndexer.PREF_AUTO, false).commit();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SQLiteDatabase writer = null;
        try {
            seed(app);
            SearchProvider provider = new SearchProvider(app);
            long before = CommunicationIndexStore.readLockGeneration();
            writer = lockIndexExclusively(app);
            Future<?> attempted = executor.submit(() -> provider.findById(ID));
            assertNull("A locked optional index must not crash the caller", attempted.get(15, TimeUnit.SECONDS));
            assertTrue("A real SQLite lock was handled", CommunicationIndexStore.readLockGeneration() > before);
            long lockedGeneration = CommunicationIndexStore.readLockGeneration();
            long started = SystemClock.elapsedRealtime();
            for (int i = 0; i < 200; i++) assertNull(provider.findById(ID));
            assertEquals("Repeated history IDs do not repeatedly reopen the busy database",
                    lockedGeneration, CommunicationIndexStore.readLockGeneration());
            assertTrue("Busy lookups do not stall every row", SystemClock.elapsedRealtime() - started < 1000L);
            writer.execSQL("ROLLBACK"); writer.close(); writer = null;
            PowerTestSupport187.await("The saved communication row returns after the lock is released",
                    () -> provider.findById(ID) instanceof CommunicationPojo);
            assertEquals("The index row was not removed", 1, rowCount(app));
        } finally {
            if (writer != null) { writer.execSQL("ROLLBACK"); writer.close(); }
            executor.shutdownNow();
            cleanup(app);
            prefs.edit().putBoolean(CommunicationIndexer.PREF_AUTO, oldAuto).commit();
        }
    }

    @Test public void busyIndexDoesNotEraseVisibleHistoryAndRetriesAfterUnlock() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        boolean oldAuto = prefs.getBoolean(CommunicationIndexer.PREF_AUTO, true);
        HistoryTestSupport188.configure(app);
        prefs.edit().putBoolean(CommunicationIndexer.PREF_AUTO, false).commit();
        seed(app);
        DBHelper.insertHistory(app, null, ID);
        SQLiteDatabase writer = null;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            HistoryTestSupport188.awaitUi(scenario, "Providers ready", a ->
                    KissApplication.getApplication(a).getDataHandler().isAllProvidersLoaded());
            scenario.onActivity(MainActivity::showHistory);
            HistoryTestSupport188.awaitUi(scenario, "Communication item is visible", a -> HistoryTestSupport188.hasRow(a, ID));
            long before = CommunicationIndexStore.readLockGeneration();
            writer = lockIndexExclusively(app);
            scenario.onActivity(a -> SearchHandler.getInstance().search(Searcher.Type.HISTORY, a, null, false));
            PowerTestSupport187.await("History encountered the real database lock", () ->
                    CommunicationIndexStore.readLockGeneration() > before);
            HistoryTestSupport188.awaitUi(scenario, "Existing History remains on screen", a ->
                    HistoryTestSupport188.hasRow(a, ID));
            assertTrue("Saved history remains in its database", HistoryTestSupport188.historyCount(app, ID) > 0);
            AtomicLong generation = (AtomicLong) HistoryTestSupport188.field(SearchHandler.getInstance(), "searchGeneration");
            AtomicLong completed = (AtomicLong) HistoryTestSupport188.field(SearchHandler.getInstance(), "completedSearchGeneration");
            long attemptedGeneration = generation.get();
            writer.execSQL("ROLLBACK"); writer.close(); writer = null;
            PowerTestSupport187.await("The one-shot retry completes after unlock", () ->
                    completed.get() == attemptedGeneration || generation.get() > attemptedGeneration);
            HistoryTestSupport188.awaitUi(scenario, "Saved row remains visible after retry", a -> HistoryTestSupport188.hasRow(a, ID));
        } finally {
            if (writer != null) { writer.execSQL("ROLLBACK"); writer.close(); }
            DBHelper.removeFromHistory(app, ID);
            cleanup(app);
            prefs.edit().putBoolean(CommunicationIndexer.PREF_AUTO, oldAuto).commit();
        }
    }

    private static void seed(Context app) {
        try (CommunicationIndexStore store = new CommunicationIndexStore(app)) {
            store.put(SOURCE, SOURCE_ID, "test.caller", "+27120000000", "Lock test 191",
                    "Saved communication", System.currentTimeMillis(), "");
        }
    }

    private static SQLiteDatabase lockIndexExclusively(Context app) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(app.getDatabasePath("smart_index.db").getPath(),
                null, SQLiteDatabase.OPEN_READWRITE);
        try (Cursor c = db.rawQuery("PRAGMA journal_mode=DELETE", null)) {
            assertTrue(c.moveToFirst());
            assertEquals("delete", c.getString(0).toLowerCase(java.util.Locale.ROOT));
        }
        db.execSQL("BEGIN EXCLUSIVE");
        return db;
    }

    private static int rowCount(Context app) {
        try (CommunicationIndexStore store = new CommunicationIndexStore(app);
             Cursor c = store.getReadableDatabase().rawQuery(
                     "SELECT COUNT(*) FROM communication_index WHERE source=? AND source_id=?",
                     new String[]{SOURCE, SOURCE_ID})) {
            assertTrue(c.moveToFirst());
            return c.getInt(0);
        }
    }

    private static void cleanup(Context app) {
        try (CommunicationIndexStore store = new CommunicationIndexStore(app)) {
            store.getWritableDatabase().delete("communication_index", "source=? AND source_id=?",
                    new String[]{SOURCE, SOURCE_ID});
        }
    }
}
