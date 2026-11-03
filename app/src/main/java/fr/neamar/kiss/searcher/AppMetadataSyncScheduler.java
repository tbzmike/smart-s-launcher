package fr.neamar.kiss.searcher;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.appusage.AppMetadataRefreshPolicy;
import fr.neamar.kiss.appusage.AppUsageTracker;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.SemanticActivityRecord;

/**
 * Persistent queue connecting Android package/app-usage update detection to app metadata refresh.
 *
 * <p>Package broadcasts can arrive while Smart S is not on screen. Pending package names are kept
 * in preferences until the metadata job reports completion, so an app update is not lost merely
 * because the launcher process is later reclaimed.</p>
 */
public final class AppMetadataSyncScheduler {
    private static final int JOB_ID = 0x53534D44; // "SSMD"
    private static final String PREF_PENDING = "app-metadata-auto-pending-packages";
    private static final String PREF_REASON_PREFIX = "app-metadata-auto-reason:";
    private static final String PREF_TOKEN_PREFIX = "app-metadata-auto-token:";
    private static final String PREF_UPDATE_PREFIX = "app-metadata-auto-update:";

    private AppMetadataSyncScheduler() { }

    /** Description refresh belongs to usage tracking or opted-in semantic descriptions. */
    public static boolean shouldRunAutomatically(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return AppUsageTracker.isEnabled(context)
                || (prefs.getBoolean("semantic-search-enabled", false)
                    && prefs.getBoolean(AppSourceMetadataUpdater.PREF_USE_SOURCE_DESCRIPTIONS, true));
    }

    /** Keep pending work for later, without leaving an unused network job active. */
    public static void syncAutomaticWork(@NonNull Context context) {
        if (shouldRunAutomatically(context)) {
            resumePending(context);
        } else {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) scheduler.cancel(JOB_ID);
            AppSourceMetadataUpdater.cancelAutomatic();
        }
    }

    public static void enqueuePackage(@NonNull Context context,
                                      @NonNull String packageName,
                                      @NonNull String reason) {
        if (TextUtils.isEmpty(packageName)) return;

        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
        long observedUpdate = 0L;
        try {
            observedUpdate = appContext.getPackageManager().getPackageInfo(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // Remembered/frozen packages can be temporarily hidden by the system.
        }

        synchronized (AppMetadataSyncScheduler.class) {
            Set<String> pending = new HashSet<>(
                    prefs.getStringSet(PREF_PENDING, Collections.emptySet()));
            if (!AppMetadataRefreshPolicy.shouldQueuePackageRevision(
                    observedUpdate, prefs.getLong(PREF_UPDATE_PREFIX + packageName, 0L),
                    pending.contains(packageName))) return;
            pending.add(packageName);
            long token = Math.max(System.currentTimeMillis(),
                    prefs.getLong(PREF_TOKEN_PREFIX + packageName, 0L) + 1L);
            prefs.edit()
                    .putStringSet(PREF_PENDING, pending)
                    .putString(PREF_REASON_PREFIX + packageName, reason)
                    .putLong(PREF_TOKEN_PREFIX + packageName, token)
                    .putLong(PREF_UPDATE_PREFIX + packageName, observedUpdate)
                    .apply();
        }

        try {
            DBHelper.insertSemanticActivity(appContext, new SemanticActivityRecord(
                    System.currentTimeMillis(),
                    "APP_METADATA_AUTO_QUEUED",
                    "package-change",
                    packageName,
                    packageName,
                    "App usage / package detector",
                    reason + " · automatic description refresh queued."));
        } catch (RuntimeException ignored) {
            // Queueing must remain functional even if transparency logging is unavailable.
        }

        schedule(appContext);
    }

    @NonNull
    public static Set<String> pendingPackages(@NonNull Context context) {
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        synchronized (AppMetadataSyncScheduler.class) {
            return new HashSet<>(prefs.getStringSet(PREF_PENDING, Collections.emptySet()));
        }
    }

    @NonNull
    public static String reasonFor(@NonNull Context context, @NonNull String packageName) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .getString(PREF_REASON_PREFIX + packageName, "App package changed");
    }

    public static Map<String, Long> pendingRequests(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                context.getApplicationContext());
        synchronized (AppMetadataSyncScheduler.class) {
            Map<String, Long> requests = new HashMap<>();
            for (String name : prefs.getStringSet(PREF_PENDING, Collections.emptySet())) {
                long token = prefs.getLong(PREF_TOKEN_PREFIX + name, 0L);
                // Older queued requests have no token yet; migrate them without losing work.
                if (token <= 0L) {
                    token = System.currentTimeMillis();
                    prefs.edit().putLong(PREF_TOKEN_PREFIX + name, token).apply();
                }
                requests.put(name, token);
            }
            return requests;
        }
    }

    public static void acknowledge(@NonNull Context context,
                                   @NonNull Map<String, Long> requests) {
        if (requests.isEmpty()) return;
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());

        synchronized (AppMetadataSyncScheduler.class) {
            Set<String> pending = new HashSet<>(
                    prefs.getStringSet(PREF_PENDING, Collections.emptySet()));
            SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String, Long> request : requests.entrySet()) {
                String packageName = request.getKey();
                if (!AppMetadataRefreshPolicy.canAcknowledge(
                        prefs.getLong(PREF_TOKEN_PREFIX + packageName, 0L),
                        request.getValue())) continue;
                pending.remove(packageName);
                editor.remove(PREF_REASON_PREFIX + packageName);
            }
            editor.putStringSet(PREF_PENDING, pending).apply();
        }
    }

    public static void removePackage(@NonNull Context context, @NonNull String packageName) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                context.getApplicationContext());
        synchronized (AppMetadataSyncScheduler.class) {
            Set<String> pending = new HashSet<>(
                    prefs.getStringSet(PREF_PENDING, Collections.emptySet()));
            if (!pending.remove(packageName)) return;
            prefs.edit().putStringSet(PREF_PENDING, pending)
                    .remove(PREF_REASON_PREFIX + packageName).apply();
        }
    }

    /** Keep queued package changes, but don't wake the phone for automatic metadata work. */
    public static boolean isInteractive(@NonNull Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null || power.isInteractive();
    }

    public static void deferPendingWhileScreenOff(@NonNull Context context) {
        if (isInteractive(context)) return;
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(JOB_ID);
        AppSourceMetadataUpdater.cancelAutomatic();
    }

    public static void resumePending(@NonNull Context context) {
        if (!shouldRunAutomatically(context) || !isInteractive(context) || pendingPackages(context).isEmpty()
                || AppSourceMetadataUpdater.isAutomaticRunning()) return;
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            for (JobInfo job : scheduler.getAllPendingJobs()) {
                if (job.getId() == JOB_ID) return;
            }
        }
        schedule(context);
    }

    public static void schedule(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        if (!shouldRunAutomatically(appContext) || !isInteractive(appContext)) return;
        JobScheduler scheduler =
                (JobScheduler) appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        JobInfo job = new JobInfo.Builder(
                JOB_ID, new ComponentName(appContext, AppMetadataSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1_500L)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_LINEAR)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }
}
