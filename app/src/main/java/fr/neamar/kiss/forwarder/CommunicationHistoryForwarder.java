package fr.neamar.kiss.forwarder;

import android.content.Intent;
import android.os.AsyncTask;

import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.index.CommunicationIndexer;

/**
 * Event-light bridge that keeps real Android call-log entries in Smart S recent history.
 * It checks only when the launcher resumes and rebuilds only when CommunicationIndexer proves a
 * newer call exists. No polling service or timer is introduced.
 */
final class CommunicationHistoryForwarder extends Forwarder {
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

    CommunicationHistoryForwarder(MainActivity mainActivity) {
        super(mainActivity);
    }

    void onResume() {
        if (!CommunicationIndexer.needsRefresh(mainActivity)) return;
        if (!refreshInFlight.compareAndSet(false, true)) return;

        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            try {
                CommunicationIndexer.rebuild(mainActivity.getApplicationContext());
            } finally {
                refreshInFlight.set(false);
            }
            // Reuse MainActivity's established provider/history refresh path. This preserves the
            // current history layout, scroll/list adapters, Vertical Cards, U style and all other
            // already-wired result surfaces rather than refreshing them independently here.
            mainActivity.sendBroadcast(new Intent(MainActivity.LOAD_OVER));
        });
    }
}
