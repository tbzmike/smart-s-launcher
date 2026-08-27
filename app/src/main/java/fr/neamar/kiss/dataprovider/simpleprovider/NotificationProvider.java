package fr.neamar.kiss.dataprovider.simpleprovider;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.NotificationTimelineStore;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.NotificationHistorySearchPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.fuzzy.MatchInfo;
import fr.neamar.kiss.utils.fuzzy.SmartMatcher;

public final class NotificationProvider extends SimpleProvider<NotificationPojo> {
    /** Keep per-keystroke history search bounded while still returning far more candidates than UI. */
    private static final int HISTORY_QUERY_LIMIT = 160;

    private final Context context;

    public NotificationProvider(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void requestResults(String query, Searcher searcher) {
        if (query == null || query.trim().isEmpty()) return;

        SharedPreferences details = details();
        Map<String, Long> activePostTimes = new HashMap<>();
        for (NotificationPojo pojo : getPojos()) {
            activePostTimes.put(pojo.id, pojo.postTime);
            MatchInfo matchInfo = SmartMatcher.match(context, query, pojo.normalizedName, pojo.getName());
            if (pojo.updateMatchingRelevance(matchInfo, false) && !searcher.addResult(pojo)) return;
        }

        // Keep live notification search independent from persisted history search. The existing Smart
        // Features switch now controls whether saved notification rows take part in launcher search.
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("smart-notification-history-search", true)) {
            return;
        }

        // Notification history is stored in SQLite already. Query it directly instead of loading the
        // whole timeline into memory on every typed character. Each matching persisted row becomes
        // a unique launcher result that deep-links back to that exact row and query.
        String trimmedQuery = query.trim();
        List<NotificationHistoryRecord> history = SmartStateStore.queryNotifications(
                context, null, Collections.singletonList(trimmedQuery), HISTORY_QUERY_LIMIT);
        for (NotificationHistoryRecord record : history) {
            if (record == null || record.dbId <= 0L) continue;

            // The current live notification is already represented above. Only suppress the exact
            // persisted copy of that same post; older rows reusing the notification ID stay searchable.
            Long activePost = activePostTimes.get(record.notificationId);
            if (activePost != null && activePost == record.postTime) continue;

            NotificationHistorySearchPojo pojo = new NotificationHistorySearchPojo(
                    context.getPackageName(), record, trimmedQuery);
            MatchInfo matchInfo = SmartMatcher.match(context, query, pojo.normalizedName, pojo.getName());
            if (!pojo.updateMatchingRelevance(matchInfo, false)) {
                // SQLite LIKE already proved this row contains the literal query. SmartMatcher can
                // occasionally reject punctuation-heavy notification bodies, so preserve that exact
                // database match with a conservative local relevance rather than dropping it.
                pojo.relevance = 1;
            }
            if (!searcher.addResult(pojo)) return;
        }
    }

    @Override
    public boolean mayFindById(String id) {
        return id != null && (id.startsWith(NotificationListener.NOTIFICATION_SCHEME)
                || id.startsWith(NotificationListener.NOTIFICATION_GROUP_SCHEME));
    }

    @Override
    public NotificationPojo findById(String id) {
        if (!mayFindById(id)) return null;
        SharedPreferences details = details();

        if (id.startsWith(NotificationListener.NOTIFICATION_SCHEME)) {
            if (NotificationListener.isNotificationActive(context, id)) {
                NotificationPojo live = buildIndividual(details, id);
                if (live != null) return live;
            }

            NotificationHistoryRecord record = NotificationTimelineStore.findLatest(context, id);
            return record == null ? null : buildPersisted(record);
        }

        for (NotificationPojo pojo : getGroupedPojos(details)) {
            if (pojo.id.equals(id)) return pojo;
        }
        return null;
    }

    @Override
    public List<NotificationPojo> getPojos() {
        SharedPreferences details = details();
        Set<String> active = NotificationListener.getVerifiedActiveNotificationIds();
        if (active.isEmpty()) return java.util.Collections.emptyList();

        List<NotificationPojo> result = new ArrayList<>(active.size());
        for (String id : new HashSet<>(active)) {
            NotificationPojo pojo = buildIndividual(details, id);
            if (pojo != null) result.add(pojo);
        }
        result.sort(Comparator.comparingLong((NotificationPojo p) -> p.postTime).reversed());
        return result;
    }

    private SharedPreferences details() {
        return context.getSharedPreferences(
                NotificationListener.DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private NotificationPojo buildIndividual(SharedPreferences details, String id) {
        if (id == null || !id.startsWith(NotificationListener.NOTIFICATION_SCHEME)) return null;
        String packageName = details.getString(id + "|package", "");
        if (packageName == null || packageName.isEmpty()) return null;
        String groupKey = details.getString(id + "|group", packageName);
        if (groupKey == null || groupKey.isEmpty()) groupKey = packageName;
        String title = clean(details.getString(id + "|title", ""));
        String text = clean(details.getString(id + "|text", ""));

        if (TextUtils.isEmpty(text) && NotificationListener.isNotificationActive(context, id)) {
            String expanded = clean(NotificationListener.getExpandedNotificationText(context, id));
            if (!TextUtils.isEmpty(expanded)) text = expanded;
        }

        if (TextUtils.isEmpty(title) && !TextUtils.isEmpty(text)) {
            int split = text.indexOf('\n');
            if (split > 0 && split <= 80) {
                title = text.substring(0, split).trim();
                text = text.substring(split + 1).trim();
            }
        }

        long postTime = details.getLong(id + "|post", 0L);
        return new NotificationPojo(
                id,
                packageName,
                getAppName(packageName),
                groupKey,
                1,
                title,
                text,
                postTime);
    }

    private NotificationPojo buildPersisted(NotificationHistoryRecord record) {
        String appName = record.appName == null || record.appName.trim().isEmpty()
                ? getAppName(record.packageName) : record.appName;
        return new NotificationPojo(
                record.notificationId,
                record.packageName,
                appName,
                record.packageName,
                1,
                clean(record.title),
                clean(record.text),
                record.postTime);
    }

    private List<NotificationPojo> getGroupedPojos(SharedPreferences details) {
        Set<String> active = NotificationListener.getVerifiedActiveNotificationIds();
        if (active.isEmpty()) return java.util.Collections.emptyList();

        Map<String, List<String>> idsByGroup = new HashMap<>();
        for (String id : new HashSet<>(active)) {
            String packageName = details.getString(id + "|package", "");
            if (packageName == null || packageName.isEmpty()) continue;
            String groupKey = details.getString(id + "|group", packageName);
            if (groupKey == null || groupKey.isEmpty()) groupKey = packageName;
            idsByGroup.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(id);
        }

        List<NotificationPojo> result = new ArrayList<>(idsByGroup.size());
        for (Map.Entry<String, List<String>> entry : idsByGroup.entrySet()) {
            NotificationPojo pojo = buildGroup(details, entry.getKey(), entry.getValue());
            if (pojo != null) result.add(pojo);
        }
        return result;
    }

    private NotificationPojo buildGroup(SharedPreferences details, String groupKey, List<String> ids) {
        if (ids.isEmpty()) return null;
        String latestId = null;
        long latestTime = Long.MIN_VALUE;
        for (String id : ids) {
            long time = details.getLong(id + "|post", 0L);
            if (latestId == null || time > latestTime) {
                latestId = id;
                latestTime = time;
            }
        }
        if (latestId == null) return null;

        String packageName = details.getString(latestId + "|package", "");
        if (packageName == null || packageName.isEmpty()) return null;
        String appName = getAppName(packageName);
        String title = clean(details.getString(latestId + "|title", ""));
        String text = clean(details.getString(latestId + "|text", ""));
        if (TextUtils.isEmpty(text) && NotificationListener.isNotificationActive(context, latestId)) {
            text = clean(NotificationListener.getExpandedNotificationText(context, latestId));
        }
        return new NotificationPojo(
                NotificationListener.getGroupId(groupKey),
                packageName,
                appName,
                groupKey,
                ids.size(),
                title,
                text,
                latestTime);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String getAppName(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            CharSequence label = info.loadLabel(pm);
            if (label != null && label.length() > 0) return label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return packageName;
    }
}
