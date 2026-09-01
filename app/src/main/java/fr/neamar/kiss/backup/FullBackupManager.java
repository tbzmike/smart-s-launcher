package fr.neamar.kiss.backup;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import fr.neamar.kiss.BuildConfig;
import fr.neamar.kiss.utils.BackupRestoreProgress;
import fr.neamar.kiss.utils.Log;

/**
 * Portable full-state backup for Smart S Launcher.
 *
 * The archive contains persistent app-owned state only. Cache/code-cache, native libraries,
 * WebView implementation state and the installed APK are intentionally excluded because they are
 * rebuildable or device-specific. Restore is staged and validated before any live data is replaced.
 */
public final class FullBackupManager {
    private static final String TAG = "FullBackupManager";
    private static final String FORMAT = "smart-s-full-backup-v1";
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final long MAX_RESTORE_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "smart-s-full-backup");
        thread.setDaemon(true);
        return thread;
    });

    private FullBackupManager() {}

    public static void backup(@NonNull Context context, @NonNull Uri destination) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> backupInternal(appContext, destination));
    }

    public static void restore(@NonNull Context context, @NonNull Uri source) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> restoreInternal(appContext, source));
    }

    private static void backupInternal(Context context, Uri destination) {
        BackupRestoreProgress progress = BackupRestoreProgress.backup(context, 100);
        try {
            checkpointDatabases(context);
            List<RootSpec> roots = rootsFor(context);
            long totalBytes = Math.max(1L, countBytes(roots));
            long[] copied = {0L};

            OutputStream raw = context.getContentResolver().openOutputStream(destination, "wt");
            if (raw == null) throw new IOException("Unable to open backup destination");
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw, BUFFER_SIZE))) {
                writeManifest(context, zip, roots);
                progress.setPercent(2);
                for (RootSpec root : roots) {
                    addRoot(zip, root, totalBytes, copied, progress);
                }
                zip.finish();
            }
            progress.complete();
            toast(context, "Full Smart S backup saved.");
        } catch (IOException | JSONException | RuntimeException e) {
            progress.fail();
            Log.e(TAG, "Full backup failed", e);
            toast(context, "Full backup failed. Your existing Smart S data was not changed.");
        }
    }

    private static void restoreInternal(Context context, Uri source) {
        BackupRestoreProgress progress = BackupRestoreProgress.restore(context, 100);
        File working = new File(context.getCacheDir(), "smart-s-restore-" + UUID.randomUUID());
        File archive = new File(working, "backup.ssb");
        File staged = new File(working, "staged");
        try {
            if (!working.mkdirs() && !working.isDirectory()) throw new IOException("Unable to create restore workspace");
            if (!staged.mkdirs() && !staged.isDirectory()) throw new IOException("Unable to create restore staging directory");

            long sourceLength = queryLength(context, source);
            copyUriToFile(context, source, archive, sourceLength, progress);
            progress.setPercent(25);

            ArchiveInfo archiveInfo = inspectArchive(context, archive);
            progress.setPercent(30);
            extractArchive(archive, staged, archiveInfo.totalBytes, progress);
            progress.setPercent(85);

            validateStaged(staged, archiveInfo.rootNames);
            progress.setPercent(88);
            replacePersistentState(context, staged, archiveInfo.rootNames, progress);
            progress.complete();
            toast(context, "Full Smart S restore complete. Restarting launcher…");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }, 900L);
        } catch (IOException | JSONException | RuntimeException e) {
            progress.fail();
            Log.e(TAG, "Full restore failed", e);
            toast(context, "Restore failed. Existing Smart S data was kept whenever replacement had not completed.");
        } finally {
            deleteRecursively(working);
        }
    }

    private static List<RootSpec> rootsFor(Context context) {
        File dataDir = new File(context.getApplicationInfo().dataDir);
        List<RootSpec> roots = new ArrayList<>();
        roots.add(new RootSpec("shared_prefs", new File(dataDir, "shared_prefs"), true));
        roots.add(new RootSpec("databases", new File(dataDir, "databases"), true));
        roots.add(new RootSpec("files", context.getFilesDir(), true));
        roots.add(new RootSpec("no_backup", context.getNoBackupFilesDir(), true));
        File external = context.getExternalFilesDir(null);
        if (external != null) roots.add(new RootSpec("external_files", external, false));
        return roots;
    }

    private static void checkpointDatabases(Context context) {
        String[] names = context.databaseList();
        if (names == null) return;
        for (String name : names) {
            if (name == null || name.endsWith("-wal") || name.endsWith("-shm") || name.endsWith("-journal")) continue;
            File dbFile = context.getDatabasePath(name);
            if (!dbFile.isFile()) continue;
            SQLiteDatabase db = null;
            Cursor cursor = null;
            try {
                db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
                cursor = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null);
                if (cursor != null) cursor.moveToFirst();
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not checkpoint database before backup: " + name);
            } finally {
                if (cursor != null) cursor.close();
                if (db != null) db.close();
            }
        }
    }

    private static void writeManifest(Context context, ZipOutputStream zip, List<RootSpec> roots)
            throws IOException, JSONException {
        JSONObject manifest = new JSONObject();
        manifest.put("format", FORMAT);
        manifest.put("package", context.getPackageName());
        manifest.put("versionCode", BuildConfig.VERSION_CODE);
        manifest.put("versionName", BuildConfig.VERSION_NAME);
        manifest.put("createdAt", System.currentTimeMillis());
        JSONArray rootNames = new JSONArray();
        for (RootSpec root : roots) rootNames.put(root.archiveName);
        manifest.put("roots", rootNames);
        byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        ZipEntry entry = new ZipEntry(MANIFEST_ENTRY);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static long countBytes(List<RootSpec> roots) {
        long total = 0L;
        for (RootSpec root : roots) total += countBytes(root.livePath);
        return total;
    }

    private static long countBytes(@Nullable File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return Math.max(0L, file.length());
        File[] children = file.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) total += countBytes(child);
        return total;
    }

    private static void addRoot(ZipOutputStream zip, RootSpec root, long totalBytes, long[] copied,
                                BackupRestoreProgress progress) throws IOException {
        String prefix = root.archiveName + "/";
        ZipEntry directory = new ZipEntry(prefix);
        zip.putNextEntry(directory);
        zip.closeEntry();
        if (!root.livePath.exists()) return;
        addPath(zip, root.livePath, prefix, totalBytes, copied, progress);
    }

    private static void addPath(ZipOutputStream zip, File file, String entryName, long totalBytes,
                                long[] copied, BackupRestoreProgress progress) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return;
            Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
            for (File child : children) {
                String childName = entryName + child.getName();
                if (child.isDirectory()) {
                    ZipEntry dir = new ZipEntry(childName + "/");
                    zip.putNextEntry(dir);
                    zip.closeEntry();
                    addPath(zip, child, childName + "/", totalBytes, copied, progress);
                } else {
                    addFile(zip, child, childName, totalBytes, copied, progress);
                }
            }
        } else {
            addFile(zip, file, entryName, totalBytes, copied, progress);
        }
    }

    private static void addFile(ZipOutputStream zip, File file, String entryName, long totalBytes,
                                long[] copied, BackupRestoreProgress progress) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(file.lastModified());
        zip.putNextEntry(entry);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                zip.write(buffer, 0, read);
                copied[0] += read;
                int percent = 2 + (int) Math.min(93L, (copied[0] * 93L) / totalBytes);
                progress.setPercent(percent);
            }
        }
        zip.closeEntry();
    }

    private static long queryLength(Context context, Uri source) {
        try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(source, "r")) {
            if (afd == null) return -1L;
            return afd.getLength();
        } catch (IOException | SecurityException e) {
            return -1L;
        }
    }

    private static void copyUriToFile(Context context, Uri source, File target, long total,
                                      BackupRestoreProgress progress) throws IOException {
        InputStream raw = context.getContentResolver().openInputStream(source);
        if (raw == null) throw new IOException("Unable to open backup file");
        byte[] buffer = new byte[BUFFER_SIZE];
        long copied = 0L;
        try (InputStream in = new BufferedInputStream(raw, BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                copied += read;
                if (copied > MAX_RESTORE_BYTES) throw new IOException("Backup file is too large");
                if (total > 0L) progress.setPercent((int) Math.min(24L, (copied * 24L) / total));
            }
        }
    }

    private static ArchiveInfo inspectArchive(Context context, File archive) throws IOException, JSONException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry manifestEntry = zip.getEntry(MANIFEST_ENTRY);
            if (manifestEntry == null) throw new IOException("Not a Smart S full backup");
            JSONObject manifest = new JSONObject(readSmallEntry(zip, manifestEntry, 256 * 1024));
            if (!FORMAT.equals(manifest.optString("format"))) throw new IOException("Unsupported backup format");
            if (!context.getPackageName().equals(manifest.optString("package"))) {
                throw new IOException("Backup belongs to a different application");
            }
            int sourceVersion = manifest.optInt("versionCode", -1);
            if (sourceVersion < 0) throw new IOException("Backup version is missing");
            if (sourceVersion > BuildConfig.VERSION_CODE) {
                throw new IOException("Backup was created by a newer Smart S version");
            }

            JSONArray roots = manifest.optJSONArray("roots");
            if (roots == null || roots.length() == 0) throw new IOException("Backup root list is missing");
            List<String> rootNames = new ArrayList<>();
            for (int i = 0; i < roots.length(); i++) {
                String root = roots.getString(i);
                if (!isAllowedRoot(root)) throw new IOException("Unknown backup root: " + root);
                rootNames.add(root);
            }

            long totalBytes = 0L;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                validateEntryName(entry.getName(), rootNames);
                long size = entry.getSize();
                if (size > 0L) {
                    totalBytes += size;
                    if (totalBytes > MAX_RESTORE_BYTES) throw new IOException("Expanded backup is too large");
                }
            }
            return new ArchiveInfo(Collections.unmodifiableList(rootNames), Math.max(1L, totalBytes));
        }
    }

    private static String readSmallEntry(ZipFile zip, ZipEntry entry, int maxBytes) throws IOException {
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > maxBytes) throw new IOException("Manifest is too large");
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void extractArchive(File archive, File staged, long totalBytes,
                                       BackupRestoreProgress progress) throws IOException {
        long extracted = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive), BUFFER_SIZE))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (MANIFEST_ENTRY.equals(entry.getName())) {
                    zip.closeEntry();
                    continue;
                }
                File target = safeTarget(staged, entry.getName());
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("Unable to create " + target);
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                        throw new IOException("Unable to create " + parent);
                    }
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            extracted += read;
                            if (extracted > MAX_RESTORE_BYTES) throw new IOException("Expanded backup is too large");
                            int percent = 30 + (int) Math.min(55L, (extracted * 55L) / totalBytes);
                            progress.setPercent(percent);
                        }
                    }
                    if (entry.getTime() > 0L) target.setLastModified(entry.getTime());
                }
                zip.closeEntry();
            }
        }
    }

    private static File safeTarget(File root, String entryName) throws IOException {
        File target = new File(root, entryName);
        String rootPath = root.getCanonicalPath() + File.separator;
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath)) throw new IOException("Unsafe backup entry");
        return target;
    }

    private static void validateEntryName(String name, List<String> roots) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains("..")) {
            throw new IOException("Unsafe backup entry");
        }
        if (MANIFEST_ENTRY.equals(name)) return;
        for (String root : roots) {
            if (name.equals(root + "/") || name.startsWith(root + "/")) return;
        }
        throw new IOException("Entry outside declared backup roots: " + name);
    }

    private static boolean isAllowedRoot(String root) {
        return "shared_prefs".equals(root)
                || "databases".equals(root)
                || "files".equals(root)
                || "no_backup".equals(root)
                || "external_files".equals(root);
    }

    private static void validateStaged(File staged, List<String> rootNames) throws IOException {
        for (String root : rootNames) {
            File dir = new File(staged, root);
            if (!dir.exists() || !dir.isDirectory()) throw new IOException("Backup section is missing: " + root);
        }
    }

    private static void replacePersistentState(Context context, File staged, List<String> rootNames,
                                               BackupRestoreProgress progress) throws IOException {
        List<Replacement> replacements = new ArrayList<>();
        try {
            List<RootSpec> liveRoots = rootsFor(context);
            for (RootSpec root : liveRoots) {
                if (!rootNames.contains(root.archiveName)) continue;
                File stagedRoot = new File(staged, root.archiveName);
                if (root.atomicInternal) {
                    File old = new File(root.livePath.getParentFile(),
                            ".smart-s-old-" + root.archiveName + "-" + UUID.randomUUID());
                    if (old.exists()) deleteRecursively(old);
                    if (root.livePath.exists() && !root.livePath.renameTo(old)) {
                        throw new IOException("Unable to preserve current " + root.archiveName);
                    }
                    if (!stagedRoot.renameTo(root.livePath)) {
                        if (old.exists()) old.renameTo(root.livePath);
                        throw new IOException("Unable to install restored " + root.archiveName);
                    }
                    replacements.add(new Replacement(root.livePath, old));
                } else {
                    File old = new File(context.getCacheDir(),
                            "smart-s-old-external-" + UUID.randomUUID());
                    if (root.livePath.exists()) copyDirectory(root.livePath, old);
                    deleteRecursively(root.livePath);
                    copyDirectory(stagedRoot, root.livePath);
                    replacements.add(new Replacement(root.livePath, old));
                }
                int done = replacements.size();
                progress.setPercent(88 + Math.min(11, done * 11 / Math.max(1, rootNames.size())));
            }
            for (Replacement replacement : replacements) deleteRecursively(replacement.oldPath);
        } catch (IOException | RuntimeException e) {
            for (int i = replacements.size() - 1; i >= 0; i--) {
                Replacement replacement = replacements.get(i);
                deleteRecursively(replacement.livePath);
                if (replacement.oldPath.exists()) replacement.oldPath.renameTo(replacement.livePath);
            }
            throw e;
        }
    }

    private static void copyDirectory(File source, File destination) throws IOException {
        if (!source.exists()) return;
        if (source.isDirectory()) {
            if (!destination.mkdirs() && !destination.isDirectory()) throw new IOException("Unable to create " + destination);
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) copyDirectory(child, new File(destination, child.getName()));
            }
            return;
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Unable to create " + parent);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = new BufferedInputStream(new FileInputStream(source), BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(destination), BUFFER_SIZE)) {
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        destination.setLastModified(source.lastModified());
    }

    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete() && file.exists()) Log.w(TAG, "Unable to delete temporary path: " + file);
    }

    private static void toast(Context context, String text) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }

    private static final class RootSpec {
        final String archiveName;
        final File livePath;
        final boolean atomicInternal;

        RootSpec(String archiveName, File livePath, boolean atomicInternal) {
            this.archiveName = archiveName;
            this.livePath = livePath;
            this.atomicInternal = atomicInternal;
        }
    }

    private static final class ArchiveInfo {
        final List<String> rootNames;
        final long totalBytes;

        ArchiveInfo(List<String> rootNames, long totalBytes) {
            this.rootNames = rootNames;
            this.totalBytes = totalBytes;
        }
    }

    private static final class Replacement {
        final File livePath;
        final File oldPath;

        Replacement(File livePath, File oldPath) {
            this.livePath = livePath;
            this.oldPath = oldPath;
        }
    }
}
