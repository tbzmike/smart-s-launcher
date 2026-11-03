package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import fr.neamar.kiss.MainActivity;

final class HistoryTestSupport188 {
    static void configure(Context app) {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putBoolean("enable-notification-history", true)
                .putBoolean("enable-app-usage-tracking", false)
                .putBoolean("smart-battery-monitor-enabled", false)
                .putBoolean("app-usage-default-repaired-v33001", true)
                .putBoolean("semantic-search-enabled", false)
                .putBoolean("freeze-history", false)
                .putBoolean("exclude-favorites-history", false)
                .putString("smart-history-layout", "vertical")
                .putString("number-of-history-results", "803")
                .putString("number-of-search-results", "155").commit();
    }

    static Object field(Object owner, String name) {
        try {
            Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    static void awaitUi(ActivityScenario<MainActivity> scenario, String label,
                        Predicate<MainActivity> ready) throws Exception {
        AtomicBoolean result = new AtomicBoolean();
        PowerTestSupport187.await(label, () -> {
            scenario.onActivity(activity -> result.set(ready.test(activity)));
            return result.get();
        });
    }

    static boolean hasRow(MainActivity activity, String id) {
        for (int i = 0; i < activity.adapter.getCount(); i++) {
            if (id.equals(activity.adapter.getItem(i).getPojoId())) return true;
        }
        return false;
    }

    static int historyCount(Context app, String id) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                app.getDatabasePath("kiss.s3db").getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor c = db.rawQuery("SELECT COUNT(*) FROM history WHERE record=?", new String[]{id})) {
            assertTrue(c.moveToFirst());
            return c.getInt(0);
        }
    }

    static void screenshot(Context app, String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = new File(app.getExternalFilesDir(null), "verification188");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        bitmap.recycle();
    }
}
