package fr.neamar.kiss.dataprovider;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.broadcast.PackageAddedRemovedHandler;
import fr.neamar.kiss.loader.LoadAppPojos;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.ContextualRanker;
import fr.neamar.kiss.utils.SemanticHints;
import fr.neamar.kiss.utils.UserHandle;
import fr.neamar.kiss.utils.fuzzy.MatchInfo;
import fr.neamar.kiss.utils.fuzzy.SmartMatcher;

public class AppProvider extends Provider<AppPojo> {
    @Override
    public void onCreate() {
        final LauncherApps launcher = ContextCompat.getSystemService(this, LauncherApps.class);
        assert launcher != null;
        launcher.registerCallback(new LauncherAppsCallback() {
            @Override public void onPackageAdded(String packageName, android.os.UserHandle user) { handleEvent(Intent.ACTION_PACKAGE_ADDED, new String[]{packageName}, user, false); }
            @Override public void onPackageChanged(String packageName, android.os.UserHandle user) { handleEvent(Intent.ACTION_PACKAGE_CHANGED, new String[]{packageName}, user, true); }
            @Override public void onPackageRemoved(String packageName, android.os.UserHandle user) { handleEvent(Intent.ACTION_PACKAGE_REMOVED, new String[]{packageName}, user, false); }
            @Override public void onPackagesAvailable(String[] packageNames, android.os.UserHandle user, boolean replacing) { handleEvent(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE, packageNames, user, replacing); }
            @Override public void onPackagesSuspended(String[] packageNames, android.os.UserHandle user) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) handleEvent(Intent.ACTION_PACKAGES_SUSPENDED, packageNames, user, false); }
            @Override public void onPackagesUnsuspended(String[] packageNames, android.os.UserHandle user) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) handleEvent(Intent.ACTION_PACKAGES_UNSUSPENDED, packageNames, user, false); }
            @Override public void onPackagesUnavailable(String[] packageNames, android.os.UserHandle user, boolean replacing) { handleEvent(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE, packageNames, user, replacing); }
            private void handleEvent(String action, String[] packageNames, android.os.UserHandle user, boolean replacing) {
                PackageAddedRemovedHandler.handleEvent(AppProvider.this, action, packageNames, new UserHandle(AppProvider.this, user), replacing);
            }
        });
        super.onCreate();
    }

    @Override public void reload() { super.reload(); this.initialize(new LoadAppPojos(this)); }

    @Override
    public void requestResults(String query, Searcher searcher) {
        Set<String> excludedFavoriteIds = KissApplication.getApplication(this).getDataHandler().getExcludedFavorites();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        List<String> semanticHints = SemanticHints.expand(query);

        for (AppPojo pojo : getPojos()) {
            if (pojo.isExcluded() && !prefs.getBoolean("enable-excluded-apps", false)) continue;
            if (excludedFavoriteIds.contains(pojo.getFavoriteId())) continue;

            MatchInfo matchInfo = SmartMatcher.match(this, query, pojo.normalizedName, pojo.getName());
            boolean match = pojo.updateMatchingRelevance(matchInfo, false);
            if (pojo.getNormalizedTags() != null) {
                matchInfo = SmartMatcher.match(this, query, pojo.getNormalizedTags(), pojo.getName());
                match = pojo.updateMatchingRelevance(matchInfo, match);
            }

            // Semantic fallback is intentionally weaker than a literal/fuzzy result.
            if (!match) {
                for (String hint : semanticHints) {
                    MatchInfo semanticMatch = SmartMatcher.match(this, hint, pojo.normalizedName, pojo.getName());
                    if (pojo.updateMatchingRelevance(semanticMatch, false)) {
                        pojo.relevance -= 140;
                        match = true;
                        break;
                    }
                }
            }

            if (match) {
                pojo.relevance += ContextualRanker.boost(pojo.getName());
                if (!searcher.addResult(pojo)) return;
            }
        }
    }

    public List<AppPojo> getAllApps() {
        List<AppPojo> pojos = getPojos(); List<AppPojo> records = new ArrayList<>(pojos.size());
        for (AppPojo pojo : pojos) { pojo.relevance = 0; records.add(pojo); }
        return records;
    }

    public List<AppPojo> getAllAppsWithoutExcluded() {
        List<AppPojo> pojos = getPojos(); List<AppPojo> records = new ArrayList<>(pojos.size());
        for (AppPojo pojo : pojos) { if (pojo.isExcluded()) continue; pojo.relevance = 0; records.add(pojo); }
        return records;
    }
}
