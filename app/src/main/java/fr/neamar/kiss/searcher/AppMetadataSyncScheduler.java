package fr.neamar.kiss.searcher;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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

    private AppMetadataSyncScheduler() { }

    public static void enqueuePackage(@NonNull Context context,
                                      @NonNull String packageName,
                                      @NonNull String reason) {
        if (TextUtils.isEmpty(packageName)) return;

        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);

        synchronized (AppMetadataSyncScheduler.class) {
            Set<String> pending = new HashSet<>(
                    prefs.getStringSet(PREF_PENDING, Collections.emptySet()));
            pending.add(packageName);
            prefs.edit()
                    .putStringSet(PREF_PENDING, pending)
                    .putString(PREF_REASON_PREFIX + packageName, reason)
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

    public static void acknowledge(@NonNull Context context, @NonNull Set<String> packages) {
        if (packages.isEmpty()) return;
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());

        synchronized (AppMetadataSyncScheduler.class) {
            Set<String> pending = new HashSet<>(
                    prefs.getStringSet(PREF_PENDING, Collections.emptySet()));
            SharedPreferences.Editor editor = prefs.edit();
            for (String packageName : packages) {
                pending.remove(packageName);
                editor.remove(PREF_REASON_PREFIX + packageName);
            }
            editor.putStringSet(PREF_PENDING, pending).apply();
        }
    }

    public static void schedule(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        JobScheduler scheduler =
                (JobScheduler) appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        JobInfo job = new JobInfo.Builder(
                JOB_ID, new ComponentName(appContext, AppMetadataSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1_500L)
                .setOverrideDeadline(30_000L)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }
}
