package fr.neamar.kiss.social;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.provider.ContactsContract;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.MimeTypeUtils;
import fr.neamar.kiss.utils.Permission;

/**
 * Orchestrates Smart S' communications/social contact index without reading private app storage.
 *
 * The actual searchable rows remain in the existing ContactsProvider and ShortcutsProvider. This
 * service discovers every Android-exposed third-party contact action and conversation shortcut,
 * records per-package index state, then reloads those providers. App-private WhatsApp/Telegram/etc.
 * databases are intentionally never opened or copied.
 */
public class SocialContactIndexService extends Service {
    private static final String TAG = SocialContactIndexService.class.getSimpleName();
    private static final String CHANNEL_ID = "social_contact_index";
    private static final int PROGRESS_NOTIFICATION_ID = 8321;
    private static final int PROMPT_NOTIFICATION_ID = 8322;
    private static final String ACTION_INDEX = "fr.neamar.kiss.social.INDEX";
    private static final String PREFS_NAME = "social-contact-index-state";
    private static final String PREF_PACKAGES = "packages";
    private static final String PREF_RUNNING = "running";
    private static final String PREF_PROMPT_SIGNATURE = "prompt-signature";

    public static final String STATUS_NOT_INDEXED = "NOT_INDEXED";
    public static final String STATUS_INDEXING = "INDEXING";
    public static final String STATUS_INDEXED = "INDEXED";
    public static final String STATUS_INDEXED_NO_EXPOSED_CONTACTS = "INDEXED_NO_EXPOSED_CONTACTS";
    public static final String STATUS_STALE = "STALE";
    public static final String STATUS_FAILED = "FAILED";

    private static final List<String> KNOWN_COMMUNICATION_PREFIXES = Arrays.asList(
            "com.whatsapp", "org.telegram", "com.facebook.orca", "com.facebook.mlite",
            "com.instagram", "com.twitter", "com.x.", "com.snapchat", "com.discord",
            "org.thoughtcrime.securesms", "com.viber", "jp.naver.line", "com.skype",
            "com.linkedin", "com.reddit", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.google.android.apps.messaging", "com.microsoft.teams", "com.slack"
    );

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void maybePrompt(Context context) {
        Context appContext = context.getApplicationContext();
        if (!Permission.checkPermission(appContext, Permission.PERMISSION_READ_CONTACTS)) return;
        if (!canPostNotifications(appContext)) return;

        LinkedHashMap<String, String> candidates = detectCandidatePackages(appContext, false);
        List<String> pending = getPendingPackages(appContext, candidates);
        if (pending.isEmpty()) return;

        String signature = buildPromptSignature(appContext, pending);
        SharedPreferences state = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (state.getBoolean(PREF_RUNNING, false)) return;
        if (signature.equals(state.getString(PREF_PROMPT_SIGNATURE, ""))) return;

        ensureChannel(appContext);
        PendingIntent indexIntent = getIndexPendingIntent(appContext);
        Notification notification = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(appContext.getString(R.string.social_index_prompt_title))
                .setContentText(appContext.getResources().getQuantityString(
                        R.plurals.social_index_prompt_body, pending.size(), pending.size()))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        appContext.getResources().getQuantityString(
                                R.plurals.social_index_prompt_body, pending.size(), pending.size())))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(indexIntent)
                .addAction(0, appContext.getString(R.string.social_index_action), indexIntent)
                .build();

        NotificationManager nm = ContextCompat.getSystemService(appContext, NotificationManager.class);
        if (nm != null) nm.notify(PROMPT_NOTIFICATION_ID, notification);
        state.edit().putString(PREF_PROMPT_SIGNATURE, signature).apply();
    }

    public static void startIndexing(Context context) {
        Intent intent = new Intent(context, SocialContactIndexService.class).setAction(ACTION_INDEX);
        ContextCompat.startForegroundService(context, intent);
    }

    public static String getPackageStatus(Context context, String packageName) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(packageName + "|status", STATUS_NOT_INDEXED);
    }

    public static long getLastIndexedAt(Context context, String packageName) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(packageName + "|indexedAt", 0L);
    }

    public static int getIndexedContactCount(Context context, String packageName) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(packageName + "|contacts", 0);
    }

    public static int getIndexedShortcutCount(Context context, String packageName) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(packageName + "|shortcuts", 0);
    }

    public static void markPackageStale(Context context, String packageName) {
        if (packageName == null || packageName.equals(context.getPackageName())) return;
        SharedPreferences state = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (state.contains(packageName + "|status")) {
            state.edit()
                    .putString(packageName + "|status", STATUS_STALE)
                    .remove(PREF_PROMPT_SIGNATURE)
                    .apply();
        }
    }

    public static void forgetPackage(Context context, String packageName) {
        SharedPreferences state = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> packages = new HashSet<>(state.getStringSet(PREF_PACKAGES, Collections.emptySet()));
        packages.remove(packageName);
        state.edit()
                .putStringSet(PREF_PACKAGES, packages)
                .remove(packageName + "|status")
                .remove(packageName + "|version")
                .remove(packageName + "|indexedAt")
                .remove(packageName + "|contacts")
                .remove(packageName + "|shortcuts")
                .remove(packageName + "|label")
                .remove(PREF_PROMPT_SIGNATURE)
                .apply();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_INDEX.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        SharedPreferences state = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (state.getBoolean(PREF_RUNNING, false)) return START_NOT_STICKY;

        state.edit().putBoolean(PREF_RUNNING, true).apply();
        startForeground(PROGRESS_NOTIFICATION_ID, buildProgressNotification(0, 1,
                getString(R.string.social_index_starting), true));
        executor.execute(() -> runIndex(startId));
        return START_NOT_STICKY;
    }

    private void runIndex(int startId) {
        SharedPreferences state = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        NotificationManager nm = ContextCompat.getSystemService(this, NotificationManager.class);
        int totalContacts = 0;
        int totalShortcuts = 0;
        try {
            LinkedHashMap<String, String> candidates = detectCandidatePackages(this, true);
            if (candidates.isEmpty()) {
                finishIndex(nm, getString(R.string.social_index_none_found));
                return;
            }

            Map<String, Set<String>> contactsByPackage = collectExposedContactKeys(candidates);
            List<String> packages = new ArrayList<>(candidates.keySet());
            Collections.sort(packages);
            Set<String> indexedPackages = new TreeSet<>();

            int completed = 0;
            for (String packageName : packages) {
                String label = candidates.get(packageName);
                state.edit()
                        .putString(packageName + "|status", STATUS_INDEXING)
                        .putString(packageName + "|label", label == null ? packageName : label)
                        .apply();

                if (nm != null && canPostNotifications(this)) {
                    nm.notify(PROGRESS_NOTIFICATION_ID,
                            buildProgressNotification(completed, packages.size(),
                                    getString(R.string.social_index_current_app,
                                            label == null ? packageName : label), true));
                }

                int contactCount = contactsByPackage.getOrDefault(packageName, Collections.emptySet()).size();
                int shortcutCount = countConversationShortcuts(packageName);
                totalContacts += contactCount;
                totalShortcuts += shortcutCount;

                String status = (contactCount + shortcutCount) > 0
                        ? STATUS_INDEXED : STATUS_INDEXED_NO_EXPOSED_CONTACTS;
                long version = getVersionCode(this, packageName);
                state.edit()
                        .putString(packageName + "|status", status)
                        .putLong(packageName + "|version", version)
                        .putLong(packageName + "|indexedAt", System.currentTimeMillis())
                        .putInt(packageName + "|contacts", contactCount)
                        .putInt(packageName + "|shortcuts", shortcutCount)
                        .putString(packageName + "|label", label == null ? packageName : label)
                        .apply();
                indexedPackages.add(packageName);

                completed++;
                if (nm != null && canPostNotifications(this)) {
                    nm.notify(PROGRESS_NOTIFICATION_ID,
                            buildProgressNotification(completed, packages.size(),
                                    getString(R.string.social_index_current_app,
                                            label == null ? packageName : label), true));
                }
            }

            state.edit().putStringSet(PREF_PACKAGES, indexedPackages).remove(PREF_PROMPT_SIGNATURE).apply();

            // The searchable data stays in the launcher's existing, optimized providers. Their
            // loaders now see every supported third-party contact MIME and every launcher shortcut.
            KissApplication app = KissApplication.getApplication(this);
            app.getDataHandler().reloadContactsProvider();
            app.getDataHandler().reloadShortcuts();
            sendBroadcast(MainActivity.internalBroadcast(this, MainActivity.LOAD_OVER));

            finishIndex(nm, getString(R.string.social_index_complete, totalContacts, totalShortcuts));
        } catch (RuntimeException e) {
            Log.e(TAG, "Social contact indexing failed", e);
            state.edit().putString("last-error", e.getClass().getSimpleName()).apply();
            finishIndex(nm, getString(R.string.social_index_failed));
        } finally {
            state.edit().putBoolean(PREF_RUNNING, false).apply();
            stopForeground(false);
            stopSelf(startId);
        }
    }

    private Map<String, Set<String>> collectExposedContactKeys(LinkedHashMap<String, String> candidates) {
        Map<String, Set<String>> result = new HashMap<>();
        PackageManager pm = getPackageManager();
        Map<String, String> mimePackageCache = new HashMap<>();

        try (Cursor cursor = getContentResolver().query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data.MIMETYPE, ContactsContract.Data.LOOKUP_KEY,
                        ContactsContract.Data._ID}, null, null, null)) {
            if (cursor == null) return result;
            int mimeIndex = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE);
            int lookupIndex = cursor.getColumnIndex(ContactsContract.Data.LOOKUP_KEY);
            int idIndex = cursor.getColumnIndex(ContactsContract.Data._ID);
            while (cursor.moveToNext()) {
                String mime = cursor.getString(mimeIndex);
                if (!MimeTypeUtils.isSocialContactMimeType(mime)) continue;
                long rowId = cursor.getLong(idIndex);
                String packageName = mimePackageCache.get(mime);
                if (!mimePackageCache.containsKey(mime)) {
                    Intent view = MimeTypeUtils.getRegisteredIntentByMimeType(this, mime, rowId, "");
                    ResolveInfo resolved = view == null ? null : pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
                    packageName = resolved == null || resolved.activityInfo == null
                            ? null : resolved.activityInfo.packageName;
                    mimePackageCache.put(mime, packageName == null ? "" : packageName);
                } else if (packageName != null && packageName.isEmpty()) {
                    packageName = null;
                }
                if (packageName == null || packageName.isEmpty()) continue;
                candidates.putIfAbsent(packageName, getAppLabel(this, packageName));
                String lookup = cursor.getString(lookupIndex);
                if (lookup == null || lookup.isEmpty()) lookup = "row:" + rowId;
                result.computeIfAbsent(packageName, key -> new HashSet<>()).add(lookup);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Contacts permission unavailable while indexing social contacts", e);
        }
        return result;
    }

    private int countConversationShortcuts(String packageName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return 0;
        LauncherApps launcherApps = ContextCompat.getSystemService(this, LauncherApps.class);
        if (launcherApps == null) return 0;
        try {
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery()
                    .setPackage(packageName)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<android.content.pm.ShortcutInfo> shortcuts = launcherApps.getShortcuts(query, Process.myUserHandle());
            if (shortcuts == null) return 0;
            int count = 0;
            for (android.content.pm.ShortcutInfo shortcut : shortcuts) {
                // Smart S' ShortcutsProvider indexes these same Android-published shortcut surfaces.
                // For a package already classified as social/communications, count every enabled
                // published shortcut rather than relying on hidden/non-SDK conversation metadata.
                if (shortcut != null && shortcut.isEnabled()) count++;
            }
            return count;
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG, "Unable to query shortcuts for " + packageName, e);
            return 0;
        }
    }

    private static LinkedHashMap<String, String> detectCandidatePackages(Context context, boolean includeContactResolvers) {
        LinkedHashMap<String, String> candidates = new LinkedHashMap<>();
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (RuntimeException e) {
            return candidates;
        }
        for (ApplicationInfo app : apps) {
            if (app == null || app.packageName == null || app.packageName.equals(context.getPackageName())) continue;
            boolean known = isKnownCommunicationPackage(app.packageName);
            boolean categorizedSocial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && app.category == ApplicationInfo.CATEGORY_SOCIAL;
            if (known || categorizedSocial) {
                CharSequence label = app.loadLabel(pm);
                candidates.put(app.packageName, label == null ? app.packageName : label.toString());
            }
        }

        if (includeContactResolvers && Permission.checkPermission(context, Permission.PERMISSION_READ_CONTACTS)) {
            try (Cursor cursor = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                    new String[]{ContactsContract.Data.MIMETYPE, ContactsContract.Data._ID}, null, null, null)) {
                if (cursor != null) {
                    int mimeIndex = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE);
                    int idIndex = cursor.getColumnIndex(ContactsContract.Data._ID);
                    Set<String> checkedMime = new HashSet<>();
                    while (cursor.moveToNext()) {
                        String mime = cursor.getString(mimeIndex);
                        if (!checkedMime.add(mime) || !MimeTypeUtils.isSocialContactMimeType(mime)) continue;
                        Intent view = MimeTypeUtils.getRegisteredIntentByMimeType(context, mime,
                                cursor.getLong(idIndex), "");
                        ResolveInfo resolved = view == null ? null : pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
                        if (resolved != null && resolved.activityInfo != null) {
                            String pkg = resolved.activityInfo.packageName;
                            candidates.putIfAbsent(pkg, getAppLabel(context, pkg));
                        }
                    }
                }
            } catch (SecurityException ignored) {
                // Permission may have been revoked between the check and query.
            }
        }
        return candidates;
    }

    private static List<String> getPendingPackages(Context context, Map<String, String> candidates) {
        SharedPreferences state = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        List<String> pending = new ArrayList<>();
        for (String packageName : candidates.keySet()) {
            String status = state.getString(packageName + "|status", STATUS_NOT_INDEXED);
            long indexedVersion = state.getLong(packageName + "|version", -1L);
            long currentVersion = getVersionCode(context, packageName);
            if (STATUS_NOT_INDEXED.equals(status) || STATUS_STALE.equals(status)
                    || STATUS_FAILED.equals(status) || indexedVersion != currentVersion) {
                pending.add(packageName);
            }
        }
        return pending;
    }

    private static String buildPromptSignature(Context context, List<String> packages) {
        List<String> signatures = new ArrayList<>();
        for (String pkg : packages) signatures.add(pkg + ':' + getVersionCode(context, pkg));
        Collections.sort(signatures);
        return signatures.toString();
    }

    private static boolean isKnownCommunicationPackage(String packageName) {
        String lower = packageName.toLowerCase(Locale.ROOT);
        for (String prefix : KNOWN_COMMUNICATION_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    private static long getVersionCode(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1L;
        }
    }

    private static String getAppLabel(Context context, String packageName) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            CharSequence label = info.loadLabel(context.getPackageManager());
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private Notification buildProgressNotification(int progress, int max, String detail, boolean ongoing) {
        int safeMax = Math.max(1, max);
        int safeProgress = Math.min(Math.max(0, progress), safeMax);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.social_index_progress_title))
                .setContentText(detail)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setProgress(safeMax, safeProgress, progress == 0)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void finishIndex(@Nullable NotificationManager nm, String detail) {
        if (nm == null || !canPostNotifications(this)) return;
        nm.notify(PROGRESS_NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.social_index_complete_title))
                .setContentText(detail)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(0, 0, false)
                .build());
    }

    private static PendingIntent getIndexPendingIntent(Context context) {
        Intent index = new Intent(context, SocialContactIndexService.class).setAction(ACTION_INDEX);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(context, 8321, index, flags);
        }
        return PendingIntent.getService(context, 8321, index, flags);
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ContextCompat.getSystemService(context, NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.social_index_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.social_index_channel_description));
        nm.createNotificationChannel(channel);
    }

    private static boolean canPostNotifications(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
