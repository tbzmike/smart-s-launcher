package fr.neamar.kiss.appusage;

import android.app.job.JobParameters;
import android.app.job.JobService;

import fr.neamar.kiss.utils.LauncherScrollWorkGate;

public final class AppUsageJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        if (!AppUsageTracker.isEnabled(this) || LauncherScrollWorkGate.isScrolling()) return false;
        Thread worker = new Thread(() -> {
            try {
                AppUsageSync.sync(getApplicationContext());
            } finally {
                jobFinished(params, false);
            }
        }, "smart-s-app-usage-job");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
