package fr.neamar.kiss.forwarder;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.index.CommunicationIndexer;

/**
 * Event-light bridge that keeps real Android call-log entries in Smart S recent history.
 * It checks only when the launcher resumes and rebuilds only when CommunicationIndexer proves a
 * refresh is needed. Routine background index maintenance does not force a visible Home reload.
 */
final class CommunicationHistoryForwarder extends Forwarder {
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

    CommunicationHistoryForwarder(MainActivity mainActivity) {
        super(mainActivity);
    }

    void onResume() {
        if (!CommunicationIndexer.needsRefresh(mainActivity)) return;
        if (!refreshInFlight.compareAndSet(false, true)) return;

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mainActivity);
        final long callHistoryBefore = prefs.getLong(
                CommunicationIndexer.PREF_CALL_HISTORY_LAST_SYNCED_TIME, 0L);
        final int enrichmentBefore = prefs.getInt(
                CommunicationIndexer.PREF_TRUECALLER_NAME_ENRICHMENT_VERSION, 0);
        final boolean communicationSearchVisible = mainActivity.searchEditText != null
                && !TextUtils.isEmpty(mainActivity.searchEditText.getText());

        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            try {
                CommunicationIndexer.rebuild(mainActivity.getApplicationContext());
            } finally {
                refreshInFlight.set(false);
            }

            SharedPreferences afterPrefs = PreferenceManager.getDefaultSharedPreferences(mainActivity);
            long callHistoryAfter = afterPrefs.getLong(
                    CommunicationIndexer.PREF_CALL_HISTORY_LAST_SYNCED_TIME, 0L);
            int enrichmentAfter = afterPrefs.getInt(
                    CommunicationIndexer.PREF_TRUECALLER_NAME_ENRICHMENT_VERSION, 0);

            boolean recentHistoryChanged = callHistoryAfter > callHistoryBefore;
            boolean oneTimeEnrichmentChanged = enrichmentAfter != enrichmentBefore;

            // Do not make navigating Home visibly refresh just because the 15-minute index timer
            // elapsed. A visible refresh is needed only when Recent History actually gained a call,
            // a one-time enrichment migration changed its display data, or the user is currently
            // searching and therefore needs the freshly rebuilt communication index immediately.
            if (recentHistoryChanged || oneTimeEnrichmentChanged || communicationSearchVisible) {
                mainActivity.sendBroadcast(MainActivity.internalBroadcast(mainActivity, MainActivity.LOAD_OVER));
            }
        });
    }
}
