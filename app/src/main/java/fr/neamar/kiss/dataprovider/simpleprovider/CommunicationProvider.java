package fr.neamar.kiss.dataprovider.simpleprovider;

import android.content.Context;
import android.os.AsyncTask;

import androidx.preference.PreferenceManager;

import java.util.List;

import fr.neamar.kiss.index.CommunicationIndexStore;
import fr.neamar.kiss.index.CommunicationIndexer;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.searcher.Searcher;

public final class CommunicationProvider extends SimpleProvider<CommunicationPojo> {
    private static final String SCHEME = "communication://";
    private final Context context;
    private volatile boolean refreshInFlight;

    public CommunicationProvider(Context context) {
        this.context = context.getApplicationContext();
        CommunicationIndexer.ensureDefaults(this.context);
        maybeRefresh();
    }

    private void maybeRefresh() {
        if (!CommunicationIndexer.needsRefresh(context) || refreshInFlight) return;
        refreshInFlight = true;
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            try { CommunicationIndexer.rebuild(context); }
            finally { refreshInFlight = false; }
        });
    }

    @Override public void requestResults(String query, Searcher searcher) {
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(CommunicationIndexer.PREF_ENABLED, true)) return;
        if (query == null || query.trim().length() < 2) return;
        maybeRefresh();
        int limit = Math.max(5, Math.min(200, PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(CommunicationIndexer.PREF_LIMIT, 40)));
        try (CommunicationIndexStore store = new CommunicationIndexStore(context)) {
            List<CommunicationPojo> results = store.search(query, limit);
            for (CommunicationPojo p : results) {
                if (searcher.isCancelled()) break;
                searcher.addResult(p);
            }
        }
    }

    @Override public boolean mayFindById(String id) { return id != null && id.startsWith(SCHEME); }

    @Override public CommunicationPojo findById(String id) {
        if (!mayFindById(id)) return null;
        try {
            long rowId = Long.parseLong(id.substring(SCHEME.length()));
            try (CommunicationIndexStore store = new CommunicationIndexStore(context)) {
                return store.find(rowId);
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
