package fr.neamar.kiss.dataprovider;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
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
    private static final long FROZEN_RECONCILE_MS = 15000L;
    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private LauncherApps launcherApps;
    private final Runnable reconcileFrozenState = new Runnable() {
        @Override public void run() {
            boolean changed = false;
            if (launcherApps != null && isLoaded()) {
                for (AppPojo pojo : getPojos()) {
                    try {
                        boolean enabled = launcherApps.isPackageEnabled(pojo.packageName, pojo.userHandle.getRealHandle())
                                && launcherApps.isActivityEnabled(pojo.getComponent(), pojo.userHandle.getRealHandle());
                        if (pojo.isDisabled() == enabled) {
                            pojo.setDisabled(!enabled);
                            changed = true;
                        }
                    } catch (SecurityException | IllegalArgumentException ignored) {
                        // Persistent catalog keeps the last safe state when Android hides a package.
                    }
                }
            }
            if (changed) {
                KissApplication.getApplication(AppProvider.this).resetIconsHandler();
                sendBroadcast(new Intent(MainActivity.LOAD_OVER));
            }
            stateHandler.postDelayed(this, FROZEN_RECONCILE_MS);
        }
    };

    @Override
    public void onCreate() {
        launcherApps = ContextCompat.getSystemService(this, LauncherApps.class);
        assert launcherApps != null;
        launcherApps.registerCallback(new LauncherAppsCallback() {
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
        stateHandler.postDelayed(reconcileFrozenState, FROZEN_RECONCILE_MS);
    }

    @Override public void onDestroy() {
        stateHandler.removeCallbacks(reconcileFrozenState);
        super.onDestroy();
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
