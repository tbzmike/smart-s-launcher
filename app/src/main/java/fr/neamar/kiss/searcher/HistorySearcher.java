package fr.neamar.kiss.searcher;

import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.os.UserManager;

import androidx.core.content.ContextCompat;
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
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.utils.RecentLaunchTracker;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.UserHandle;

/** Retrieve pojos from history. */
public class HistorySearcher extends Searcher {
    private final SharedPreferences prefs;

    public HistorySearcher(MainActivity activity, boolean isRefresh) {
        super(activity, "<history>", isRefresh);
        prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    @Override
    protected int getMaxResultCount() {
        try {
            return Double.valueOf(prefs.getString("number-of-display-elements",
                    String.valueOf(DEFAULT_MAX_RESULTS))).intValue();
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_RESULTS;
        }
    }

    @Override
    protected Void doInBackground(Void... voids) {
        boolean excludeFavorites = prefs.getBoolean("exclude-favorites-history", false);

        MainActivity activity = activityWeakReference.get();
        if (activity == null) return null;

        DataHandler dataHandler = KissApplication.getApplication(activity).getDataHandler();
        Set<String> excludedFromHistory = dataHandler.getExcludedFromHistory();
        Set<String> excludedPojoById = new HashSet<>(excludedFromHistory);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            for (String id : excludedFromHistory) {
                Pojo pojo = dataHandler.getItemById(id);
                if (pojo instanceof AppPojo) {
                    List<ShortcutInfo> shortcutInfos = ShortcutUtil.getShortcuts(
                            activity, ((AppPojo) pojo).packageName);
                    for (ShortcutInfo shortcutInfo : shortcutInfos) {
                        ShortcutRecord shortcutRecord = ShortcutUtil.createShortcutRecord(
                                activity, shortcutInfo, !shortcutInfo.isPinned());
                        if (shortcutRecord != null) {
                            excludedPojoById.add(ShortcutUtil.generateShortcutId(
                                    new UserHandle(activity, shortcutInfo.getUserHandle()),
                                    shortcutRecord));
                        }
                    }
                }
            }
        }

        if (excludeFavorites) {
            for (Pojo favoritePojo : dataHandler.getFavorites()) {
                excludedPojoById.add(favoritePojo.id);
            }
        }

        List<Pojo> pojos = dataHandler.getHistory(activity, getMaxResultCount(), excludedPojoById);
        restoreMissingHistoryEntries(activity, dataHandler, pojos, excludedPojoById);
        pinActiveNotificationTimeline(activity, pojos, excludedPojoById);
        pinMostRecentLaunch(activity, dataHandler, pojos, excludedPojoById);

        this.addResults(pojos);
        return null;
    }

    /**
     * DataHandler intentionally skips history ids that no currently connected provider can resolve.
     * Dynamic shortcuts can legitimately enter that state after they have been launched, even though
     * their persistent history row is still valid. Rehydrate every missing entry in the current
     * history window, not only the newest one, so a shortcut moves upward naturally as newer items
     * arrive instead of disappearing from the timeline.
     */
    private void restoreMissingHistoryEntries(MainActivity activity, DataHandler dataHandler,
                                              List<Pojo> pojos, Set<String> excludedPojoById) {
        int max = getMaxResultCount();
        if (max <= 0 || pojos.size() >= max) return;

        HistoryMode historyMode = dataHandler.getHistoryMode();
        int extendedLimit = max + excludedPojoById.size();
        List<ValuedHistoryRecord> historyRecords = DBHelper.getHistory(
                activity, extendedLimit, historyMode);
        int historySize = historyRecords.size();

        for (int i = 0; i < historySize && pojos.size() < max; i++) {
            String historyId = historyRecords.get(i).record;
            if (historyId == null || excludedPojoById.contains(historyId)
                    || indexOfHistoryId(pojos, historyId) >= 0) {
                continue;
            }

            Pojo recovered = RecentLaunchTracker.resolve(historyId);
            if (recovered == null) recovered = dataHandler.getItemById(historyId);
            if (recovered == null && historyId.startsWith(ShortcutPojo.SCHEME)) {
                recovered = resolveRememberedShortcut(activity, dataHandler, historyId);
            }
            if (recovered == null || excludedPojoById.contains(recovered.id)) continue;

            recovered.relevance = historyMode == HistoryMode.ALPHABETICALLY
                    ? 0
                    : historySize - i;
            pojos.add(recovered);
        }
    }

    /** Keep the exact most recently selected launcher result at the final history position. */
    private void pinMostRecentLaunch(MainActivity activity, DataHandler dataHandler,
                                     List<Pojo> pojos, Set<String> excludedPojoById) {
        if (getMaxResultCount() <= 0) return;

        Pojo recentPojo = RecentLaunchTracker.getMostRecent();
        String mostRecentId = recentPojo == null ? null : recentPojo.getHistoryId();

        // The exact object selected in this launcher process is authoritative. Dynamic shortcuts
        // can disappear from LauncherApps immediately after launch even though the launch succeeded.
        // Only fall back to the persistent history DB when there is no process-local selection.
        if (recentPojo == null) {
            List<ValuedHistoryRecord> mostRecent = DBHelper.getHistory(activity, 1, HistoryMode.RECENCY);
            if (mostRecent.isEmpty()) return;
            mostRecentId = mostRecent.get(0).record;
            if (mostRecentId == null) return;

            for (Pojo pojo : pojos) {
                if (mostRecentId.equals(pojo.getHistoryId())) {
                    recentPojo = pojo;
                    break;
                }
            }

            if (recentPojo == null) recentPojo = dataHandler.getItemById(mostRecentId);
            if (recentPojo == null) recentPojo = RecentLaunchTracker.resolve(mostRecentId);
            if (recentPojo == null && mostRecentId.startsWith(ShortcutPojo.SCHEME)) {
                recentPojo = resolveRememberedShortcut(activity, dataHandler, mostRecentId);
            }
        }

        if (recentPojo == null || mostRecentId == null) return;
        if (excludedPojoById.contains(mostRecentId) || excludedPojoById.contains(recentPojo.id)) return;

        int existingIndex = indexOfHistoryId(pojos, mostRecentId);
        if (existingIndex >= 0) {
            recentPojo = pojos.remove(existingIndex);
        } else if (pojos.size() >= getMaxResultCount() && !pojos.isEmpty()) {
            int removeIndex = indexOfLowestRelevance(pojos, recentPojo.id);
            if (removeIndex >= 0) pojos.remove(removeIndex);
        }

        recentPojo.relevance = Integer.MAX_VALUE;
        pojos.add(recentPojo);
    }

    private int indexOfHistoryId(List<Pojo> pojos, String historyId) {
        for (int i = 0; i < pojos.size(); i++) {
            Pojo pojo = pojos.get(i);
            if (pojo != null && historyId.equals(pojo.getHistoryId())) return i;
        }
        return -1;
    }

    private int indexOfLowestRelevance(List<Pojo> pojos, String protectedId) {
        int index = -1;
        int relevance = Integer.MAX_VALUE;
        for (int i = 0; i < pojos.size(); i++) {
            Pojo candidate = pojos.get(i);
            if (candidate == null || protectedId.equals(candidate.id)) continue;
            if (index < 0 || candidate.relevance < relevance) {
                index = i;
                relevance = candidate.relevance;
            }
        }
        return index;
    }

    /** Rebuild an exact remembered shortcut when LauncherApps temporarily stops exposing it. */
    private Pojo resolveRememberedShortcut(MainActivity activity, DataHandler dataHandler,
                                            String requestedId) {
        UserManager userManager = ContextCompat.getSystemService(activity, UserManager.class);
        if (userManager == null) return null;

        for (ShortcutRecord record : DBHelper.getShortcuts(activity)) {
            if (record == null || record.packageName == null || record.intentUri == null) continue;
            for (android.os.UserHandle profile : userManager.getUserProfiles()) {
                UserHandle user = new UserHandle(activity, profile);
                if (!requestedId.equals(ShortcutUtil.generateShortcutId(user, record))) continue;

                ShortcutPojo pojo = new ShortcutPojo(user, record, null,
                        true, false, true);
                pojo.setName(record.name);
                pojo.setTags(dataHandler.getTagsHandler().getTags(pojo.id));
                return pojo;
            }
        }
        return null;
    }

    /**
     * Active notifications form a dedicated chronological band near the bottom of history. The
     * user's latest successful launch is pinned after this band and therefore remains the final item.
     */
    private void pinActiveNotificationTimeline(MainActivity activity, List<Pojo> pojos,
                                               Set<String> excludedPojoById) {
        if (!prefs.getBoolean("enable-notification-history", false)) return;

        int max = getMaxResultCount();
        if (max <= 0) return;

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
        if (activity == null) return false;

        DataHandler dataHandler = KissApplication.getApplication(activity).getDataHandler();
        if (dataHandler.getHistoryMode() != HistoryMode.ALPHABETICALLY) {
            for (Pojo pojo : pojos) {
                if (pojo.isDisabled() && pojo.relevance != Integer.MAX_VALUE) {
                    pojo.relevance -= 200;
                }
            }
        }

        return super.addResults(pojos);
    }
}
