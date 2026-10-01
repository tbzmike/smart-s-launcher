package fr.neamar.kiss.searcher;

import android.content.SharedPreferences;
import android.os.CancellationSignal;
import android.os.UserManager;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.activitylauncher.ActivityLauncherStore;
import fr.neamar.kiss.dataprovider.simpleprovider.NotificationProvider;
import fr.neamar.kiss.db.AppCatalogRecord;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.HistoryMode;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.db.ValuedHistoryRecord;
import fr.neamar.kiss.notification.NotificationDisplayDeduplicator;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.notification.NotificationTimelineState;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.RecentLaunchTracker;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.UserHandle;

/** Retrieve pojos from history. */
public class HistorySearcher extends Searcher {
    private final SharedPreferences prefs;
    private final CancellationSignal databaseCancellation = new CancellationSignal();
    private NotificationProvider notificationProvider;
    private List<ShortcutRecord> shortcutRecords;
    private Map<String, AppCatalogRecord> rememberedAppsByHistoryId;
    private Map<String, UserHandle> rememberedAppUsersByHistoryId;

    public HistorySearcher(MainActivity activity, boolean isRefresh) {
        super(activity, "<history>", isRefresh);
        prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    @Override
    protected int getMaxResultCount() {
        try {
            String legacyValue = prefs.getString("number-of-display-elements",
                    String.valueOf(DEFAULT_MAX_RESULTS));
            return Double.valueOf(prefs.getString("number-of-history-results",
                    legacyValue)).intValue();
        } catch (NumberFormatException | ClassCastException e) {
            return DEFAULT_MAX_RESULTS;
        }
    }

    @Override
    protected Void doInBackground(Void... voids) {
        if (shouldAbort()) return null;
        boolean excludeFavorites = prefs.getBoolean("exclude-favorites-history", false);

        MainActivity activity = activityWeakReference.get();
        if (activity == null || shouldAbort()) return null;

        DataHandler dataHandler = KissApplication.getApplication(activity).getDataHandler();
        Set<String> excludedFromHistory = dataHandler.getExcludedFromHistory();
        Set<String> excludedPojoById = new HashSet<>(excludedFromHistory);
        Set<String> excludedPackages = new HashSet<>();
        List<AppPojo> applications = dataHandler.getApplications();
        if (applications != null) {
            for (AppPojo app : applications) {
                if (shouldAbort()) return null;
                if (app != null && app.isExcludedFromHistory()) {
                    excludedPackages.add(app.packageName);
                }
            }
        }

        // App rows and shortcut rows are independent launch targets. DataHandler records the exact
        // id that was launched, so an app-level history exclusion must not be expanded into every
        // shortcut published by that app. Exact shortcut ids remain independently excludable.
        if (excludeFavorites) {
            for (Pojo favoritePojo : dataHandler.getFavorites()) {
                if (shouldAbort()) return null;
                excludedPojoById.add(favoritePojo.id);
            }
        }

        int max = getMaxResultCount();
        if (max <= 0 || shouldAbort()) return null;

        // One authoritative history read per pass. The previous implementation read the same
        // history window three times and then retried every unresolved entry immediately.
        List<ValuedHistoryRecord> historyRecords = DBHelper.getHistoryByRecency(
                activity, max + excludedPojoById.size(), databaseCancellation);
        if (shouldAbort()) return null;

        List<Pojo> pojos = getStrictRecencyHistory(
                activity, dataHandler, excludedPojoById, historyRecords, max);
        if (shouldAbort()) return null;

        pinActiveNotificationTimeline(activity, pojos, excludedPojoById, excludedPackages);
        if (shouldAbort()) return null;

        pinMostRecentPersistedLaunch(
                activity, dataHandler, pojos, excludedPojoById, historyRecords, max);
        if (shouldAbort()) return null;

        for (int i = pojos.size() - 1; i >= 0; i--) {
            if (shouldAbort()) return null;
            if (belongsToExcludedApp(pojos.get(i), excludedPojoById, excludedPackages)) {
                pojos.remove(i);
            }
        }
        collapseDuplicateNotifications(activity, pojos);
        if (shouldAbort()) return null;

        this.addResults(pojos);
        return null;
    }

    public void cancelDatabaseWork() {
        databaseCancellation.cancel();
    }

    private boolean shouldAbort() {
        return isCancelled()
                || Thread.currentThread().isInterrupted()
                || SearchHandler.getInstance().isHistoryScrollActive();
    }

    /** Resolve the visible History list from the database's strict newest-first RECENCY chain. */
    private List<Pojo> getStrictRecencyHistory(MainActivity activity, DataHandler dataHandler,
                                               Set<String> excludedPojoById,
                                               List<ValuedHistoryRecord> records,
                                               int max) {
        if (max <= 0 || records == null || records.isEmpty()) return new ArrayList<>();

        List<Pojo> history = new ArrayList<>(Math.min(max, records.size()));
        int recordCount = records.size();

        for (int i = 0; i < recordCount && history.size() < max; i++) {
            if (shouldAbort()) return history;
            String historyId = records.get(i).record;
            if (historyId == null || excludedPojoById.contains(historyId)) continue;

            // A DB history row is authoritative. Resolve it through the live provider first, then
            // the exact recently launched object, then the persisted shortcut catalog. Doing this
            // in the primary recency pass prevents the later full refresh from replacing a correct
            // warm Home row with a list that temporarily cannot resolve a dynamic shortcut.
            Pojo pojo = resolveHistoryTarget(activity, dataHandler, historyId);
            if (pojo == null || excludedPojoById.contains(pojo.id)) continue;

            pojo.relevance = HistoryRecencyOrder.relevanceForNewestFirstIndex(recordCount, i);
            history.add(pojo);
        }
        return history;
    }

    // Missing entries are already resolved through live providers, RecentLaunchTracker and the
    // persisted shortcut catalog in the primary pass. Retrying the same ids immediately was
    // duplicate work and could multiply database/provider cost without adding information.

    /**
     * Keep the newest successfully persisted history item at the final history position.
     * The database is authoritative for recency. RecentLaunchTracker is used only to recover
     * the selected object when a provider temporarily cannot resolve the persisted history id.
     */
    private void pinMostRecentPersistedLaunch(MainActivity activity, DataHandler dataHandler,
                                              List<Pojo> pojos, Set<String> excludedPojoById,
                                              List<ValuedHistoryRecord> historyRecords,
                                              int max) {
        if (max <= 0 || historyRecords == null || historyRecords.isEmpty() || shouldAbort()) return;

        String mostRecentId = historyRecords.get(0).record;
        if (mostRecentId == null || excludedPojoById.contains(mostRecentId)) return;

        Pojo recentPojo = null;
        int existingIndex = indexOfHistoryId(pojos, mostRecentId);
        if (existingIndex >= 0) {
            recentPojo = pojos.remove(existingIndex);
        }

        if (recentPojo == null && !shouldAbort()) {
            recentPojo = resolveHistoryTarget(activity, dataHandler, mostRecentId);
        }
        if (recentPojo == null || excludedPojoById.contains(recentPojo.id)) return;

        if (existingIndex < 0 && pojos.size() >= max && !pojos.isEmpty()) {
            int removeIndex = indexOfLowestRelevance(pojos, recentPojo.id);
            if (removeIndex >= 0) pojos.remove(removeIndex);
        }

        // RelevanceComparator emits lower relevance first, so MAX_VALUE guarantees the newest
        // persisted launch is the final/bottom row. Once another item is persisted, normal DB
        // relevance moves this item upward one position at a time.
        recentPojo.relevance = Integer.MAX_VALUE;
        pojos.add(recentPojo);
    }

    /**
     * Resolve one persisted history identity without changing what was launched. The DB id remains
     * the authority; provider state is only one possible representation of that exact target.
     */
    private Pojo resolveHistoryTarget(MainActivity activity, DataHandler dataHandler,
                                      String historyId) {
        Pojo pojo = dataHandler.getItemById(historyId);
        if (pojo == null) pojo = RecentLaunchTracker.resolve(historyId);
        if (pojo == null
                && (historyId.startsWith(NotificationListener.NOTIFICATION_SCHEME)
                || historyId.startsWith(NotificationListener.NOTIFICATION_GROUP_SCHEME))) {
            pojo = notificationProvider(activity).findById(historyId);
        }
        if (pojo == null && historyId.startsWith("app://")) {
            pojo = resolveRememberedApp(activity, dataHandler, historyId);
        }
        if (pojo == null && historyId.startsWith(ShortcutPojo.SCHEME)) {
            pojo = resolveRememberedShortcut(activity, dataHandler, historyId);
        }
        return pojo;
    }

    /**
     * Provider reloads, package freezing and IceBox can temporarily hide an app from LauncherApps.
     * The history database must not lose that launch merely because the live provider cannot resolve
     * it at this instant. Reconstruct the exact app:// identity from the persistent app catalog
     * written by LoadAppPojos. The catalog is loaded at most once per HistorySearcher pass.
     */
    private Pojo resolveRememberedApp(MainActivity activity, DataHandler dataHandler,
                                      String requestedId) {
        if (rememberedAppsByHistoryId == null || rememberedAppUsersByHistoryId == null) {
            rememberedAppsByHistoryId = new HashMap<>();
            rememberedAppUsersByHistoryId = new HashMap<>();

            UserManager userManager = ContextCompat.getSystemService(activity, UserManager.class);
            if (userManager == null) return null;

            for (android.os.UserHandle profile : userManager.getUserProfiles()) {
                if (shouldAbort()) return null;
                long serial = userManager.getSerialNumberForUser(profile);
                if (serial < 0L) continue;
                UserHandle user = new UserHandle(activity, profile);
                for (AppCatalogRecord record : SmartStateStore.getRememberedApps(activity, serial)) {
                    if (shouldAbort()) return null;
                    if (record == null || record.packageName == null
                            || record.activityName == null) continue;
                    String historyId = user.addUserSuffixToString(
                            "app://" + record.packageName + "/" + record.activityName, '/');
                    rememberedAppsByHistoryId.put(historyId, record);
                    rememberedAppUsersByHistoryId.put(historyId, user);
                }
            }
        }

        AppCatalogRecord record = rememberedAppsByHistoryId.get(requestedId);
        UserHandle user = rememberedAppUsersByHistoryId.get(requestedId);
        if (record == null || user == null) return null;

        boolean excludedFromHistory = dataHandler.getExcludedFromHistory().contains(requestedId);
        boolean disabled = user.isCurrentUser()
                && !AppLaunchUtils.isPackageEnabled(activity, record.packageName);
        AppPojo app = new AppPojo(requestedId, record.packageName, record.activityName, user,
                false, excludedFromHistory, false, disabled);
        app.setName(record.label == null ? record.packageName : record.label);
        app.setTags(dataHandler.getTagsHandler().getTags(app.id));
        RecentLaunchTracker.remember(app);
        return app;
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

        if (shortcutRecords == null) {
            if (shouldAbort()) return null;
            shortcutRecords = DBHelper.getShortcuts(activity, databaseCancellation);
            if (shouldAbort()) return null;
        }

        for (ShortcutRecord record : shortcutRecords) {
            if (shouldAbort()) return null;
            if (record == null || record.packageName == null || record.intentUri == null) continue;

            // Activity Launcher/IceBox-style managed targets use a content-derived stable id rather
            // than ShortcutUtil.generateShortcutId(). Recover them with the same identity used by
            // LoadShortcutsPojos and by the history writer, so provider reloads cannot orphan them.
            if (ActivityLauncherStore.isManaged(record)) {
                String stableId = ActivityLauncherStore.stablePojoId(record);
                if (requestedId.equals(stableId)) {
                    ShortcutPojo pojo = new ShortcutPojo(UserHandle.OWNER, record, null,
                            true, false, false, stableId);
                    pojo.setName(ActivityLauncherStore.displayLabel(activity, record));
                    pojo.setTags(dataHandler.getTagsHandler().getTags(pojo.id));
                    return pojo;
                }
                continue;
            }

            if (userManager == null) continue;
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
     * newest persisted user launch is pinned after this band and therefore remains the final item.
     */
    private void pinActiveNotificationTimeline(MainActivity activity, List<Pojo> pojos,
                                               Set<String> excludedPojoById,
                                               Set<String> excludedPackages) {
        if (!prefs.getBoolean("enable-notification-history", false)) return;

        int max = getMaxResultCount();
        if (max <= 0) return;

        pojos.removeIf(pojo -> pojo instanceof NotificationPojo
                && pojo.id.startsWith(NotificationListener.NOTIFICATION_GROUP_SCHEME));

        if (shouldAbort()) return;
        List<NotificationPojo> newestFirst = new ArrayList<>(
                notificationProvider(activity).getPojos());
        newestFirst.removeIf(notification -> NotificationTimelineState.isHiddenFromHistory(
                activity, notification.exactNotificationId, notification.postTime));
        if (newestFirst.isEmpty()) return;
        if (newestFirst.size() > max) {
            newestFirst = new ArrayList<>(newestFirst.subList(0, max));
        }
        newestFirst.sort(Comparator.comparingLong(p -> p.postTime));

        Set<String> activeIds = new HashSet<>();
        for (NotificationPojo notification : newestFirst) {
            if (shouldAbort()) return;
            activeIds.add(notification.id);
        }

        int base = Integer.MAX_VALUE - newestFirst.size() - 2;
        for (Pojo pojo : pojos) {
            if (shouldAbort()) return;
            if (!(pojo instanceof NotificationPojo) && pojo.relevance > base) {
                pojo.relevance = base;
            }
        }

        int order = 0;
        for (NotificationPojo notification : newestFirst) {
            if (shouldAbort()) return;
            if (excludedPojoById.contains(notification.id)
                    || excludedPackages.contains(notification.packageName)) continue;
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

    /**
     * Remove duplicate notification tiles after the live timeline and persisted history have been
     * merged. Different Android notification keys are intentionally preserved in storage/routing;
     * only the redundant visible row is dropped. Prefer a currently-active route, then the newest
     * post, then the row already ranked closest to the bottom.
     */
    private void collapseDuplicateNotifications(MainActivity activity, List<Pojo> pojos) {
        for (int i = 0; i < pojos.size(); i++) {
            if (shouldAbort()) return;
            if (!(pojos.get(i) instanceof NotificationPojo)) continue;
            NotificationPojo incumbent = (NotificationPojo) pojos.get(i);

            for (int j = pojos.size() - 1; j > i; j--) {
                if (shouldAbort()) return;
                if (!(pojos.get(j) instanceof NotificationPojo)) continue;
                NotificationPojo candidate = (NotificationPojo) pojos.get(j);
                if (!NotificationDisplayDeduplicator.isNearDuplicate(
                        incumbent.packageName, incumbent.groupKey,
                        incumbent.latestTitle, incumbent.latestText, incumbent.postTime,
                        candidate.packageName, candidate.groupKey,
                        candidate.latestTitle, candidate.latestText, candidate.postTime)) {
                    continue;
                }

                if (preferNotification(activity, candidate, incumbent)) {
                    pojos.set(i, candidate);
                    incumbent = candidate;
                }
                pojos.remove(j);
            }
        }
    }

    private NotificationProvider notificationProvider(MainActivity activity) {
        if (notificationProvider == null) {
            notificationProvider = new NotificationProvider(activity);
        }
        return notificationProvider;
    }

    private boolean preferNotification(MainActivity activity, NotificationPojo candidate,
                                       NotificationPojo incumbent) {
        boolean candidateActive = NotificationListener.isNotificationActive(
                activity, candidate.exactNotificationId);
        boolean incumbentActive = NotificationListener.isNotificationActive(
                activity, incumbent.exactNotificationId);
        if (candidateActive != incumbentActive) return candidateActive;
        if (candidate.postTime != incumbent.postTime) return candidate.postTime > incumbent.postTime;
        return candidate.relevance > incumbent.relevance;
    }

    private boolean belongsToExcludedApp(Pojo pojo, Set<String> excludedPojoById,
                                         Set<String> excludedPackages) {
        if (pojo == null) return false;
        if (excludedPojoById.contains(pojo.id)) return true;
        if (pojo instanceof AppPojo) {
            AppPojo app = (AppPojo) pojo;
            return app.isExcludedFromHistory() || excludedPackages.contains(app.packageName);
        }
        if (pojo instanceof ShortcutPojo) {
            // A shortcut is an independently launched history target. DataHandler writes the
            // shortcut's exact history id, and the warm Home snapshot also filters by that exact id.
            // Do not later erase the shortcut merely because its publisher/target app is excluded
            // as an app row; that mismatch is what caused a shortcut to appear briefly on return
            // and disappear when the authoritative HistorySearcher completed.
            return false;
        }
        if (pojo instanceof NotificationPojo) {
            return excludedPackages.contains(((NotificationPojo) pojo).packageName);
        }
        return false;
    }

    @Override
    public boolean addResults(List<? extends Pojo> pojos) {
        MainActivity activity = activityWeakReference.get();
        if (activity == null) return false;

        DataHandler dataHandler = KissApplication.getApplication(activity).getDataHandler();
        if (dataHandler.getHistoryMode() != HistoryMode.ALPHABETICALLY) {
            for (Pojo pojo : pojos) {
                // Shortcuts must retain their true history relevance even when their publisher or
                // target app is temporarily disabled. Otherwise an older shortcut jumps hundreds
                // of relevance points instead of simply moving upward as newer launches arrive.
                if (pojo.isDisabled() && !(pojo instanceof ShortcutPojo)
                        && pojo.relevance != Integer.MAX_VALUE) {
                    pojo.relevance -= 200;
                }
            }
        }

        return super.addResults(pojos);
    }
}
