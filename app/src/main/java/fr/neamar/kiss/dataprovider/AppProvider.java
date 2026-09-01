package fr.neamar.kiss.dataprovider;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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

public class AppProvider extends Provider<AppPojo>
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    // LauncherApps callbacks are the primary source of package-state changes. This periodic pass is
    // only a fallback for freezer/root tools that can bypass callbacks, so it must never compete
    // with Home rendering or search on the main thread.
    private static final long FROZEN_RECONCILE_INITIAL_DELAY_MS = 2500L;
    private static final String PREF_DETECT_FROZEN = "smart-detect-frozen-apps";
    private static final String PREF_KEEP_FROZEN_SEARCHABLE = "smart-keep-frozen-searchable";
    private static final String PREF_PACKAGE_MONITORING = "smart-package-change-monitoring";
    private static final String PREF_RECONCILE_INTERVAL = "smart-frozen-refresh-interval";
    private static volatile boolean launcherUiVisible;
    private static volatile AppProvider activeInstance;

    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService stateExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean reconcileRunning = new AtomicBoolean(false);
    private LauncherApps launcherApps;
    private SharedPreferences prefs;

    private final LauncherAppsCallback launcherAppsCallback = new LauncherAppsCallback() {
        @Override public void onPackageAdded(String packageName, android.os.UserHandle user) {
            handleEvent(Intent.ACTION_PACKAGE_ADDED, new String[]{packageName}, user, false);
        }

        @Override public void onPackageChanged(String packageName, android.os.UserHandle user) {
            handleEvent(Intent.ACTION_PACKAGE_CHANGED, new String[]{packageName}, user, true);
        }

        @Override public void onPackageRemoved(String packageName, android.os.UserHandle user) {
            handleEvent(Intent.ACTION_PACKAGE_REMOVED, new String[]{packageName}, user, false);
        }

        @Override public void onPackagesAvailable(String[] packageNames, android.os.UserHandle user, boolean replacing) {
            handleEvent(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE, packageNames, user, replacing);
        }

        @Override public void onPackagesSuspended(String[] packageNames, android.os.UserHandle user) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                handleEvent(Intent.ACTION_PACKAGES_SUSPENDED, packageNames, user, false);
            }
        }

        @Override public void onPackagesUnsuspended(String[] packageNames, android.os.UserHandle user) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                handleEvent(Intent.ACTION_PACKAGES_UNSUSPENDED, packageNames, user, false);
            }
        }

        @Override public void onPackagesUnavailable(String[] packageNames, android.os.UserHandle user, boolean replacing) {
            handleEvent(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE, packageNames, user, replacing);
        }

        private void handleEvent(String action, String[] packageNames,
                                 android.os.UserHandle user, boolean replacing) {
            if (prefs != null && !prefs.getBoolean(PREF_PACKAGE_MONITORING, true)) return;
            PackageAddedRemovedHandler.handleEvent(AppProvider.this, action, packageNames,
                    new UserHandle(AppProvider.this, user), replacing);
        }
    };

    private final Runnable reconcileFrozenState = () -> {
        if (!launcherUiVisible || !isFrozenDetectionEnabled()) return;
        long reconcileDelayMs = getFrozenReconcileDelayMs();
        if (reconcileDelayMs < 0L) return;
        if (!isLoaded()) {
            scheduleNextReconcile(Math.min(FROZEN_RECONCILE_INITIAL_DELAY_MS, reconcileDelayMs));
            return;
        }
        if (!reconcileRunning.compareAndSet(false, true)) return;

        // Snapshot the immutable provider list on the main thread, then perform PackageManager and
        // LauncherApps calls on a dedicated background worker. Those binder calls were previously
        // executed for every app directly on the UI thread every 15 seconds and immediately on
        // every Home return.
        final List<AppPojo> snapshot = new ArrayList<>(getPojos());
        stateExecutor.execute(() -> {
            final boolean[] enabledStates = new boolean[snapshot.size()];
            PackageManager pm = getPackageManager();

            for (int i = 0; i < snapshot.size(); i++) {
                AppPojo pojo = snapshot.get(i);
                boolean enabled = true;
                try {
                    ApplicationInfo appInfo = pm.getApplicationInfo(
                            pojo.packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
                    int state = pm.getApplicationEnabledSetting(pojo.packageName);
                    enabled = appInfo.enabled
                            && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                            && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
                } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
                    enabled = false;
                }

                if (enabled && launcherApps != null) {
                    try {
                        enabled = launcherApps.isPackageEnabled(
                                pojo.packageName, pojo.userHandle.getRealHandle())
                                && launcherApps.isActivityEnabled(
                                pojo.getComponent(), pojo.userHandle.getRealHandle());
                    } catch (SecurityException | IllegalArgumentException ignored) {
                        // PackageManager state remains authoritative when LauncherApps hides it.
                    }
                }
                enabledStates[i] = enabled;
            }

            stateHandler.post(() -> {
                try {
                    if (!launcherUiVisible) return;
                    boolean changed = false;
                    for (int i = 0; i < snapshot.size(); i++) {
                        AppPojo pojo = snapshot.get(i);
                        boolean enabled = enabledStates[i];
                        if (pojo.isDisabled() == enabled) {
                            pojo.setDisabled(!enabled);
                            changed = true;
                        }
                    }

                    if (changed) {
                        // Disabled state is rendered as an ImageView filter; the underlying app icon
                        // did not change, so clearing the entire icon cache here only forces needless
                        // disk decoding and visible icon pop-in.
                        sendBroadcast(MainActivity.internalBroadcast(this, MainActivity.LOAD_OVER));
                    }
                } finally {
                    reconcileRunning.set(false);
                    if (launcherUiVisible && isFrozenDetectionEnabled())
                        scheduleNextReconcile(reconcileDelayMs);
                }
            });
        });
    };

    /**
     * Keep fallback frozen-app reconciliation active only while its results can be seen.
     * LauncherApps callbacks remain registered continuously. The fallback starts after Home has had
     * time to draw and then runs at a low cadence on a background worker.
     */
    public static void setLauncherUiVisible(boolean visible) {
        boolean changed = launcherUiVisible != visible;
        launcherUiVisible = visible;
        AppProvider provider = activeInstance;
        if (changed && provider != null) provider.updateFrozenReconcileSchedule(visible);
    }

    @Override
    public void onCreate() {
        activeInstance = this;
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        launcherApps = ContextCompat.getSystemService(this, LauncherApps.class);
        assert launcherApps != null;
        launcherApps.registerCallback(launcherAppsCallback);
        super.onCreate();
        updateFrozenReconcileSchedule(launcherUiVisible);
    }

    @Override public void onDestroy() {
        stateHandler.removeCallbacks(reconcileFrozenState);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        reconcileRunning.set(false);
        stateExecutor.shutdownNow();
        if (launcherApps != null) {
            try {
                launcherApps.unregisterCallback(launcherAppsCallback);
            } catch (RuntimeException ignored) {
                // Service teardown can race with LauncherApps binder shutdown.
            }
        }
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }

    private boolean isFrozenDetectionEnabled() {
        return prefs == null || prefs.getBoolean(PREF_DETECT_FROZEN, true);
    }

    private long getFrozenReconcileDelayMs() {
        if (prefs == null) return -1L;
        String value = prefs.getString(PREF_RECONCILE_INTERVAL, "15");
        if (value == null || "package-only".equals(value)) return -1L;
        try {
            long seconds = Long.parseLong(value);
            return Math.max(15L, Math.min(300L, seconds)) * 1000L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (PREF_DETECT_FROZEN.equals(key)) {
            updateFrozenReconcileSchedule(launcherUiVisible);
            reload();
        } else if (PREF_RECONCILE_INTERVAL.equals(key)) {
            updateFrozenReconcileSchedule(launcherUiVisible);
        } else if (PREF_KEEP_FROZEN_SEARCHABLE.equals(key)) {
            sendBroadcast(MainActivity.internalBroadcast(this, MainActivity.LOAD_OVER));
        }
    }

    private void updateFrozenReconcileSchedule(boolean visible) {
        stateHandler.removeCallbacks(reconcileFrozenState);
        if (!visible || !isFrozenDetectionEnabled()) return;
        long delayMs = getFrozenReconcileDelayMs();
        if (delayMs >= 0L) scheduleNextReconcile(Math.min(FROZEN_RECONCILE_INITIAL_DELAY_MS, delayMs));
    }

    private void scheduleNextReconcile(long delayMs) {
        stateHandler.removeCallbacks(reconcileFrozenState);
        stateHandler.postDelayed(reconcileFrozenState, delayMs);
    }

    @Override public void reload() { super.reload(); this.initialize(new LoadAppPojos(this)); }

    @Override
    public void requestResults(String query, Searcher searcher) {
        Set<String> excludedFavoriteIds = KissApplication.getApplication(this).getDataHandler().getExcludedFavorites();
        List<String> semanticHints = SemanticHints.expand(query);

        int checked = 0;
        for (AppPojo pojo : getPojos()) {
            if ((checked++ & 31) == 0 && searcher.isCancelled()) return;
            if (pojo.isExcluded() && !prefs.getBoolean("enable-excluded-apps", false)) continue;
            if (pojo.isDisabled()
                    && (!isFrozenDetectionEnabled()
                    || !prefs.getBoolean(PREF_KEEP_FROZEN_SEARCHABLE, true))) continue;
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
