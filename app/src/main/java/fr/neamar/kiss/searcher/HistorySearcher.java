package fr.neamar.kiss.searcher;

import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.dataprovider.simpleprovider.NotificationProvider;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.HistoryMode;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.db.ValuedHistoryRecord;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.UserHandle;

/**
 * Retrieve pojos from history
 */
public class HistorySearcher extends Searcher {
    private final SharedPreferences prefs;

    public HistorySearcher(MainActivity activity, boolean isRefresh) {
        super(activity, "<history>", isRefresh);
        prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    @Override
    protected int getMaxResultCount() {
        // Convert `"number-of-display-elements"` to double first before truncating to int to avoid
        // `java.lang.NumberFormatException` crashes for values larger than `Integer.MAX_VALUE`
        try {
            return Double.valueOf(prefs.getString("number-of-display-elements", String.valueOf(DEFAULT_MAX_RESULTS))).intValue();
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_RESULTS;
        }
    }

    @Override
    protected Void doInBackground(Void... voids) {
        // Ask for records
        boolean excludeFavorites = prefs.getBoolean("exclude-favorites-history", false);

        MainActivity activity = activityWeakReference.get();
        if (activity == null)
            return null;

        DataHandler dataHandler = KissApplication.getApplication(activity).getDataHandler();

        //Gather excluded
        Set<String> excludedFromHistory = dataHandler.getExcludedFromHistory();
        Set<String> excludedPojoById = new HashSet<>(excludedFromHistory);

        // add ids of shortcuts for excluded apps
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            for (String id : excludedFromHistory) {
                Pojo pojo = dataHandler.getItemById(id);
                if (pojo instanceof AppPojo) {
                    List<ShortcutInfo> shortcutInfos = ShortcutUtil.getShortcuts(activity, ((AppPojo) pojo).packageName);
                    for (ShortcutInfo shortcutInfo : shortcutInfos) {
                        ShortcutRecord shortcutRecord = ShortcutUtil.createShortcutRecord(activity, shortcutInfo, !shortcutInfo.isPinned());
                        if (shortcutRecord != null) {
                            excludedPojoById.add(ShortcutUtil.generateShortcutId(new UserHandle(activity, shortcutInfo.getUserHandle()), shortcutRecord));
                        }
                    }
                }
            }
        }

        if (excludeFavorites) {
            // Gather favorites
            for (Pojo favoritePojo : dataHandler.getFavorites()) {
                excludedPojoById.add(favoritePojo.id);
            }
        }

        List<Pojo> pojos = dataHandler.getHistory(activity, getMaxResultCount(), excludedPojoById);
        pinMostRecentLaunch(activity, dataHandler, pojos, excludedPojoById);
        pinActiveNotificationTimeline(activity, pojos, excludedPojoById);

        this.addResults(pojos);
        return null;
    }

    /**
     * The user's last tap is stronger than the configured long-term history ranking.
     * Keep that item at maximum relevance so the shared adapter places it at the
     * first/focus position used by vertical, horizontal and Square-U renderers.
     */
    private void pinMostRecentLaunch(MainActivity activity, DataHandler dataHandler,
                                     List<Pojo> pojos, Set<String> excludedPojoById) {
        if (getMaxResultCount() <= 0) return;

        List<ValuedHistoryRecord> mostRecent = DBHelper.getHistory(activity, 1, HistoryMode.RECENCY);
        if (mostRecent.isEmpty()) return;

        String mostRecentId = mostRecent.get(0).record;
        if (mostRecentId == null || excludedPojoById.contains(mostRecentId)) return;

        Pojo recentPojo = null;
        for (Pojo pojo : pojos) {
            if (mostRecentId.equals(pojo.id)) {
                recentPojo = pojo;
                break;
            }
        }

        if (recentPojo == null) {
            recentPojo = dataHandler.getItemById(mostRecentId);
            if (recentPojo == null) return;

            if (pojos.size() >= getMaxResultCount() && !pojos.isEmpty()) {
                pojos.remove(pojos.size() - 1);
            }
            pojos.add(recentPojo);
        }

        // Searcher emits low relevance first and highest relevance last. All launcher
        // history styles focus/scroll to that final item, so MAX_VALUE guarantees the
        // just-launched app or shortcut remains immediately accessible.
        recentPojo.relevance = Integer.MAX_VALUE;
    }

    /**
     * Active notifications form a dedicated chronological band at the bottom of history. This is
     * independent of Frequency/Frecency/Adaptive history ranking: an incoming notification must not
     * disappear among old frequently-launched apps. Oldest active notification is first in the
     * band, newest is last/closest to the search bar.
     */
    private void pinActiveNotificationTimeline(MainActivity activity, List<Pojo> pojos,
                                               Set<String> excludedPojoById) {
        int max = getMaxResultCount();
        if (max <= 0) return;

        // Group cards were the old model. Do not display both the old group and new individual
        // notification tile during migration.
        pojos.removeIf(pojo -> pojo instanceof NotificationPojo
                && pojo.id.startsWith(NotificationListener.NOTIFICATION_GROUP_SCHEME));

        List<NotificationPojo> newestFirst = new NotificationProvider(activity).getPojos();
        if (newestFirst.isEmpty()) return;
        if (newestFirst.size() > max) {
            newestFirst = new ArrayList<>(newestFirst.subList(0, max));
        } else {
            newestFirst = new ArrayList<>(newestFirst);
        }
        newestFirst.sort(Comparator.comparingLong(p -> p.postTime));

        Set<String> activeIds = new HashSet<>();
        for (NotificationPojo notification : newestFirst) activeIds.add(notification.id);

        // Reserve the highest relevance range exclusively for the active notification timeline.
        int base = Integer.MAX_VALUE - newestFirst.size() - 2;
        for (Pojo pojo : pojos) {
            if (!(pojo instanceof NotificationPojo) && pojo.relevance > base) {
                pojo.relevance = base;
            }
        }

        int order = 0;
        for (NotificationPojo notification : newestFirst) {
            if (excludedPojoById.contains(notification.id)) continue;
            Pojo existing = null;
            for (Pojo pojo : pojos) {
                if (notification.id.equals(pojo.id)) {
                    existing = pojo;
                    break;
                }
            }

            if (existing == null) {
                while (pojos.size() >= max && !pojos.isEmpty()) {
                    int removeIndex = -1;
                    for (int i = 0; i < pojos.size(); i++) {
                        if (!activeIds.contains(pojos.get(i).id)) {
                            removeIndex = i;
                            break;
                        }
                    }
                    if (removeIndex < 0) removeIndex = 0;
                    pojos.remove(removeIndex);
                }
                pojos.add(notification);
                existing = notification;
            }

            existing.relevance = base + 1 + order++;
        }
    }

    @Override
    public boolean addResults(List<? extends Pojo> pojos) {
        MainActivity activity = activityWeakReference.get();
        if (activity == null) {
            return false;
        }

        DataHandler dataHandler = KissApplication.getApplication(activity).getDataHandler();
        if (dataHandler.getHistoryMode() != HistoryMode.ALPHABETICALLY) {
            for (Pojo pojo : pojos) {
                if (pojo.isDisabled()) {
                    // Give penalty for disabled items, these should not be preferred
                    pojo.relevance -= 200;
                }
            }
        }

        return super.addResults(pojos);
    }
}
