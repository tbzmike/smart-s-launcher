package fr.neamar.kiss.searcher;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import java.util.Set;
import java.util.Map;
import java.util.HashMap;

import fr.neamar.kiss.KissApplication;

/** Runs the persistent package-update metadata queue with network access. */
public final class AppMetadataSyncJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        Map<String, Long> requests = AppMetadataSyncScheduler.pendingRequests(this);
        Set<String> pending = requests.keySet();
        if (pending.isEmpty()) return false;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        StringBuilder reason = new StringBuilder("Automatic package metadata refresh");
        int shown = 0;
        for (String packageName : pending) {
            if (shown >= 3) break;
            String itemReason = AppMetadataSyncScheduler.reasonFor(this, packageName);
            if (itemReason == null || itemReason.isEmpty()) continue;
            reason.append(shown == 0 ? " · " : "; ").append(itemReason);
            shown++;
        }

        boolean started = AppSourceMetadataUpdater.refreshPackages(
                this,
                KissApplication.getApplication(this).getDataHandler(),
                prefs,
                pending,
                reason.toString(),
                completed -> {
                    Map<String, Long> acknowledged = new HashMap<>();
                    for (String name : completed) {
                        Long token = requests.get(name);
                        if (token != null) acknowledged.put(name, token);
                    }
                    AppMetadataSyncScheduler.acknowledge(this, acknowledged);
                    // Unexpected failed packages and newer revisions remain pending for retry.
                    jobFinished(params, !AppMetadataSyncScheduler.pendingPackages(this).isEmpty());
                });
        if (!started) {
            // A manual refresh or another accepted job already owns the single updater.
            new Handler(Looper.getMainLooper()).post(() -> jobFinished(params, true));
        }
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Pending package names remain persisted until completion, so ask JobScheduler to retry.
        return true;
    }
}
