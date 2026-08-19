package fr.neamar.kiss.searcher;

import androidx.preference.PreferenceManager;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.dataprovider.simpleprovider.NotificationProvider;

/**
 * Minimalistic-mode searcher. Normal history stays hidden, but live notification groups remain
 * visible so hiding KISS history never hides active notifications.
 */
public class NullSearcher extends Searcher {

    public NullSearcher(MainActivity activity) {
        super(activity, "<null>", false);
    }

    @Override
    protected void displayActivityLoader() {
        // Don't display the loader for the NullSearcher
        // (otherwise, pressing home again in minimalistic mode displays the loader for no reason)
    }

    @Override
    protected Void doInBackground(Void... voids) {
        MainActivity activity = activityWeakReference.get();
        if (activity == null || isCancelled()) return null;
        if (!PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean("enable-notification-history", false)) return null;

        addResults(new NotificationProvider(activity).getPojos());
        return null;
    }
}
