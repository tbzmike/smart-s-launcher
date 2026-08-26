package fr.neamar.kiss.dataprovider.simpleprovider;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.NotificationTimelineStore;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.fuzzy.MatchInfo;
import fr.neamar.kiss.utils.fuzzy.SmartMatcher;

public final class NotificationProvider extends SimpleProvider<NotificationPojo> {
    private final Context context;

    public NotificationProvider(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void requestResults(String query, Searcher searcher) {
        if (query == null || query.trim().isEmpty()) return;
        for (NotificationPojo pojo : getPojos()) {
            MatchInfo matchInfo = SmartMatcher.match(context, query, pojo.normalizedName, pojo.getName());
            if (pojo.updateMatchingRelevance(matchInfo, false) && !searcher.addResult(pojo)) return;
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
        String title = details.getString(id + "|title", "");
        String text = details.getString(id + "|text", "");
        long postTime = details.getLong(id + "|post", 0L);
        return new NotificationPojo(
                id,
                packageName,
                getAppName(packageName),
                groupKey,
                1,
                title == null ? "" : title,
                text == null ? "" : text,
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
                record.title == null ? "" : record.title,
                record.text == null ? "" : record.text,
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
        String title = details.getString(latestId + "|title", "");
        String text = details.getString(latestId + "|text", "");
        return new NotificationPojo(
                NotificationListener.getGroupId(groupKey),
                packageName,
                appName,
                groupKey,
                ids.size(),
                title == null ? "" : title,
                text == null ? "" : text,
                latestTime);
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
