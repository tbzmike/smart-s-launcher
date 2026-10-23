package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import fr.neamar.kiss.backup.FullBackupManager;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ShortcutRecord;

/** Real Android archive tests: no destructive restore of the emulator's running database. */
@RunWith(AndroidJUnit4.class)
public final class BackupRestore180RegressionTest {
    @Test public void fullBackupIncludesEveryPreferenceStoreAndOriginalTagShortcutBinding()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String marker = "backup-180-regression-key";
        String shortcutId = "shortcut://com.example.backup180/oreo-shortcut/important";
        File archive = new File(context.getCacheDir(), "backup180-regression.ssb");
        ShortcutRecord shortcut = new ShortcutRecord();
        shortcut.name = "Backup 180";
        shortcut.packageName = "com.example.backup180";
        shortcut.intentUri = "oreo-shortcut/important";
        shortcut.targetPackage = "com.example.realapp";
        try {
            assertTrue(context.getSharedPreferences("backup180-named-test", Context.MODE_PRIVATE)
                    .edit().putString(marker, "named-preference-preserved").commit());
            assertTrue(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().putBoolean(marker, true).commit());
            DBHelper.insertShortcut(context, shortcut);
            DBHelper.insertTagsForId(context, "my-personal-tag", shortcutId);
            Method backup = FullBackupManager.class.getDeclaredMethod(
                    "backupInternal", Context.class, Uri.class);
            backup.setAccessible(true);
            backup.invoke(null, context, Uri.fromFile(archive));
            try (ZipFile zip = new ZipFile(archive)) {
                assertNotNull(zip.getEntry("manifest.json"));
                assertNotNull(zip.getEntry("tag-links.json"));
                assertNotNull(zip.getEntry("shared_prefs/backup180-named-test.xml"));
                assertNotNull(zip.getEntry("databases/kiss.s3db"));
                JSONObject manifest = new JSONObject(new String(
                        zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),
                        StandardCharsets.UTF_8));
                assertEquals(1, manifest.getInt("tagLinksVersion"));
                JSONObject links = new JSONObject(new String(
                        zip.getInputStream(zip.getEntry("tag-links.json")).readAllBytes(),
                        StandardCharsets.UTF_8));
                JSONArray arr = links.getJSONArray("links");
                boolean found = false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    if (shortcutId.equals(item.optString("id"))) {
                        assertEquals("my-personal-tag", item.getString("tags"));
                        assertEquals("shortcut", item.getString("kind"));
                        assertEquals(shortcut.packageName, item.getString("package"));
                        assertEquals(shortcut.intentUri, item.getString("intentUri"));
                        assertEquals(shortcut.targetPackage, item.getString("targetPackage"));
                        found = true;
                    }
                }
                assertTrue("Original tag/shortcut identity must survive export", found);
            }
        } finally {
            DBHelper.deleteTagsForId(context, shortcutId);
            DBHelper.removeShortcut(context, shortcut.packageName, shortcut.intentUri);
            context.getSharedPreferences("backup180-named-test", Context.MODE_PRIVATE)
                    .edit().remove(marker).commit();
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().remove(marker).commit();
            archive.delete();
        }
    }

    @Test public void legacyV1ArchiveWithoutPortableIndexIsStillRecognized() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = new File(context.getCacheDir(), "backup180-legacy.ssb");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
                JSONObject manifest = new JSONObject();
                manifest.put("format", "smart-s-full-backup-v1");
                manifest.put("package", context.getPackageName());
                manifest.put("versionCode", 606);
                JSONArray roots = new JSONArray();
                roots.put("shared_prefs"); roots.put("databases"); roots.put("files"); roots.put("no_backup");
                manifest.put("roots", roots);
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                for (int i = 0; i < roots.length(); i++) {
                    zip.putNextEntry(new ZipEntry(roots.getString(i) + "/"));
                    zip.closeEntry();
                }
            }
            Method inspect = FullBackupManager.class.getDeclaredMethod(
                    "inspectArchive", Context.class, File.class);
            inspect.setAccessible(true);
            Object archiveInfo = inspect.invoke(null, context, file);
            assertNotNull(archiveInfo);
        } finally {
            file.delete();
        }
    }
}
