package fr.neamar.kiss.appusage;

import android.app.job.JobParameters;
import android.app.job.JobService;

import fr.neamar.kiss.utils.LauncherScrollWorkGate;

public final class AppUsageJobService extends JobService {
    private Thread worker;
    @Override
    public boolean onStartJob(JobParameters params) {
        if (!AppUsageTracker.isEnabled(this) || LauncherScrollWorkGate.isScrolling()) return false;
        worker = new Thread(() -> {
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
        if (worker != null) worker.interrupt();
        return AppUsageTracker.isEnabled(this);
    }
}
