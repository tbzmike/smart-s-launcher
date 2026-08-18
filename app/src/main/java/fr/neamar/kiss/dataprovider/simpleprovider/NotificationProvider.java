package fr.neamar.kiss.dataprovider.simpleprovider;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        return id != null && id.startsWith(NotificationListener.NOTIFICATION_SCHEME);
    }

    @Override
    public NotificationPojo findById(String id) {
        if (!mayFindById(id)) return null;
        SharedPreferences details = context.getSharedPreferences(NotificationListener.DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);
        Set<String> active = details.getStringSet(NotificationListener.ACTIVE_NOTIFICATION_IDS, Collections.emptySet());
        if (active == null || !active.contains(id)) return null;
        return buildPojo(details, id);
    }

    @Override
    public List<NotificationPojo> getPojos() {
        SharedPreferences details = context.getSharedPreferences(NotificationListener.DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);
        Set<String> active = details.getStringSet(NotificationListener.ACTIVE_NOTIFICATION_IDS, Collections.emptySet());
        if (active == null || active.isEmpty()) return Collections.emptyList();

        List<NotificationPojo> result = new ArrayList<>(active.size());
        for (String id : new HashSet<>(active)) {
            NotificationPojo pojo = buildPojo(details, id);
            if (pojo != null) result.add(pojo);
        }
        result.sort(Comparator.comparingLong((NotificationPojo p) -> p.postTime).reversed());
        return result;
    }

    private NotificationPojo buildPojo(SharedPreferences details, String id) {
        String packageName = details.getString(id + "|package", "");
        if (packageName == null || packageName.isEmpty()) return null;

        String appName = packageName;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            CharSequence label = info.loadLabel(pm);
            if (label != null && label.length() > 0) appName = label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        String title = details.getString(id + "|title", "");
        String text = details.getString(id + "|text", "");
        long postTime = details.getLong(id + "|post", 0L);
        return new NotificationPojo(id, packageName, appName,
                title == null ? "" : title,
                text == null ? "" : text,
                postTime);
    }
}
