package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import androidx.preference.PreferenceManager;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.util.zip.*;
import fr.neamar.kiss.backup.FullBackupManager;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ShortcutRecord;

/** Verifies that complete backups retain preferences and exact saved tag/shortcut IDs. */
public final class FullBackupLinkRegressionTest {
    @Test public void archiveKeepsExactShortcutAndTag() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String pkg = ctx.getPackageName();
        ShortcutRecord shortcut = new ShortcutRecord();
        shortcut.packageName = pkg;
        shortcut.targetPackage = pkg;
        shortcut.name = "Backup test shortcut";
        shortcut.intentUri = "oreo-shortcut/backup-v330180";
        String id = "shortcut://" + pkg + "/" + shortcut.intentUri;
        File archive = new File(ctx.getCacheDir(), "backup-v330180-test.ssb");
        File snapshot = new File(ctx.getCacheDir(), "backup-v330180-test.db");
        try {
            assertTrue(PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                    .putString("backup180-test-preference", "preserved").commit());
            DBHelper.initDatabase(ctx);
            DBHelper.insertShortcut(ctx, shortcut);
            DBHelper.insertTagsForId(ctx, "backup180test", id);
            FullBackupManager.backup(ctx, Uri.fromFile(archive));

            ZipFile zip = null;
            for (int i = 0; i < 300; i++) {
                try {
                    zip = new ZipFile(archive);
                    if (zip.getEntry("manifest.json") != null) break;
                    zip.close();
                    zip = null;
                } catch (IOException incomplete) { }
                Thread.sleep(100L);
            }
            assertNotNull("Backup must finish", zip);
            try (ZipFile complete = zip) {
                ZipEntry manifestEntry = complete.getEntry("manifest.json");
                assertNotNull(manifestEntry);
                JSONObject manifest;
                try (InputStream in = complete.getInputStream(manifestEntry)) {
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    byte[] bytes = new byte[8192];
                    int read;
                    while ((read = in.read(bytes)) != -1) data.write(bytes, 0, read);
                    manifest = new JSONObject(data.toString("UTF-8"));
                }
                assertEquals("smart-s-full-backup-v1", manifest.getString("format"));
                assertEquals(608, manifest.getInt("versionCode"));
                assertTrue(manifest.getJSONObject("sha256").has("databases/kiss.s3db"));
                ZipEntry database = complete.getEntry("databases/kiss.s3db");
                assertNotNull(database);
                try (InputStream in = complete.getInputStream(database);
                     OutputStream out = new FileOutputStream(snapshot)) {
                    byte[] data = new byte[8192];
                    int count;
                    while ((count = in.read(data)) != -1) out.write(data, 0, count);
                }
                boolean settingsIncluded = false;
                java.util.Enumeration<? extends ZipEntry> entries = complete.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("shared_prefs/") && name.endsWith(".xml")) {
                        settingsIncluded = true;
                    }
                }
                assertTrue("Saved settings XML included", settingsIncluded);
            }
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    snapshot.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                try (Cursor row = db.rawQuery(
                        "SELECT tag FROM tags WHERE record=?", new String[]{id})) {
                    assertTrue("Original tag record preserved", row.moveToFirst());
                    assertEquals("backup180test", row.getString(0));
                }
                try (Cursor row = db.rawQuery(
                        "SELECT target_package FROM shortcuts WHERE package=? AND intent_uri=?",
                        new String[]{pkg, shortcut.intentUri})) {
                    assertTrue("Original shortcut identity preserved", row.moveToFirst());
                    assertEquals(pkg, row.getString(0));
                }
            }
        } finally {
            DBHelper.deleteTagsForId(ctx, id);
            DBHelper.removeShortcut(ctx, pkg, shortcut.intentUri);
            PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                    .remove("backup180-test-preference").commit();
            archive.delete();
            snapshot.delete();
        }
    }
}
