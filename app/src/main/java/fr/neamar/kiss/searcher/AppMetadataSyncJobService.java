package fr.neamar.kiss.searcher;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.Set;

import fr.neamar.kiss.KissApplication;

/** Runs the persistent package-update metadata queue with network access. */
public final class AppMetadataSyncJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        Set<String> pending = AppMetadataSyncScheduler.pendingPackages(this);
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

        AppSourceMetadataUpdater.refreshPackages(
                this,
                KissApplication.getApplication(this).getDataHandler(),
                prefs,
                pending,
                reason.toString(),
                () -> {
                    AppMetadataSyncScheduler.acknowledge(this, pending);
                    if (!AppMetadataSyncScheduler.pendingPackages(this).isEmpty()) {
                        AppMetadataSyncScheduler.schedule(this);
                    }
                    jobFinished(params, false);
                });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Pending package names remain persisted until completion, so ask JobScheduler to retry.
        return true;
    }
}
