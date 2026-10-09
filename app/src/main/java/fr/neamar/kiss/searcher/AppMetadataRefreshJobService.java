package fr.neamar.kiss.searcher;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.util.List;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.utils.Log;

/**
 * Durable bridge between package/App Usage update detection and app-description retrieval.
 *
 * <p>Package broadcasts should never perform network work directly. This job waits for Android to
 * report network connectivity, survives the originating broadcast, and then runs the exact-package
 * metadata refresh. The updater callback keeps the JobService alive until that refresh finishes.</p>
 */
public final class AppMetadataRefreshJobService extends JobService {
    private static final String TAG = AppMetadataRefreshJobService.class.getSimpleName();
    private static final String EXTRA_PACKAGE = "package";
    private static final String EXTRA_REASON = "reason";
    private static final int JOB_NAMESPACE = 0x53000000;

    public static boolean schedule(@NonNull Context context,
                                   @NonNull String packageName,
                                   @NonNull String reason) {
        if (TextUtils.isEmpty(packageName)
                || packageName.equals(context.getPackageName())) {
            return false;
        }

        JobScheduler scheduler = (JobScheduler)
                context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return false;

        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_PACKAGE, packageName);
        extras.putString(EXTRA_REASON, reason);

        JobInfo job = new JobInfo.Builder(
                jobId(packageName),
                new ComponentName(context, AppMetadataRefreshJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1_500L)
                .setPersisted(true)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(extras)
                .build();

        try {
            return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to schedule metadata refresh for " + packageName, e);
            return false;
        }
    }

    public static int pendingJobCount(@NonNull Context context) {
        JobScheduler scheduler = (JobScheduler)
                context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return 0;
        try {
            List<JobInfo> jobs = scheduler.getAllPendingJobs();
            int count = 0;
            ComponentName expected =
                    new ComponentName(context, AppMetadataRefreshJobService.class);
            for (JobInfo job : jobs) {
                if (job != null && expected.equals(job.getService())) count++;
            }
            return count;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        PersistableBundle extras = params == null ? null : params.getExtras();
        String packageName = extras == null ? null : extras.getString(EXTRA_PACKAGE);
        String reason = extras == null ? null : extras.getString(EXTRA_REASON);
        if (TextUtils.isEmpty(packageName)) return false;
        if (TextUtils.isEmpty(reason)) reason = "Automatic package metadata refresh";

        final String finalReason = reason;
        boolean queued;
        try {
            queued = AppSourceMetadataUpdater.refreshPackage(
                    this,
                    KissApplication.getApplication(this).getDataHandler(),
                    PreferenceManager.getDefaultSharedPreferences(this),
                    packageName,
                    finalReason,
                    () -> jobFinished(params, false));
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to start metadata refresh job for " + packageName, e);
            return false;
        }

        // A matching refresh may already be running/queued in-process. In that case the existing
        // request is authoritative and this duplicate durable job can finish immediately.
        return queued;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // If Android stops the job for constraints/process pressure, schedule it again later.
        return true;
    }

    private static int jobId(String packageName) {
        return JOB_NAMESPACE | (packageName.hashCode() & 0x00FFFFFF);
    }
}
