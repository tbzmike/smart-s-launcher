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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.broadcast.PackageAddedRemovedHandler;
import fr.neamar.kiss.loader.LoadAppPojos;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.ContextualRanker;
import fr.neamar.kiss.utils.FrozenAppPreferences;
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
    private static volatile boolean launcherUiVisible;
    private static volatile boolean launcherScrolling;
    private static volatile AppProvider activeInstance;
    // Package/freezer callbacks can arrive while Android is bringing HOME to the foreground.
    // Never restart the expensive canonical app scan in that critical window. Coalesce it and run
    // once after the first Home frame has had time to render.
    private static final long HOME_RELOAD_GRACE_MS = 900L;
    private boolean deferredPackageReload;
    private boolean deferredShortcutReload;
    private final Runnable deferredPackageReloadRunnable = () -> {
        if (!launcherUiVisible) return;
        boolean apps = deferredPackageReload;
        boolean shortcuts = deferredShortcutReload;
        deferredPackageReload = false;
        deferredShortcutReload = false;
        if (apps) reload();
        if (shortcuts) {
            KissApplication.getApplication(this).getDataHandler().reloadShortcuts();
        }
    };

    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService stateExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "smart-s-frozen-state");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final AtomicBoolean reconcileRunning = new AtomicBoolean(false);
    private volatile Future<?> reconcileFuture;
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
            if (!FrozenAppPreferences.monitorPackageChanges(AppProvider.this)) return;
            PackageAddedRemovedHandler.handleEvent(AppProvider.this, action, packageNames,
                    new UserHandle(AppProvider.this, user), replacing);
        }
    };

    private final Runnable reconcileFrozenState = () -> {
        if (!launcherUiVisible || !isFrozenDetectionEnabled()) return;
        if (launcherScrolling) {
            return;
        }
        long reconcileDelayMs = getFrozenReconcileDelayMs();
        if (reconcileDelayMs < 0L) return;
        if (!isLoaded()) {
            scheduleNextReconcile(Math.min(FROZEN_RECONCILE_INITIAL_DELAY_MS, reconcileDelayMs));
            return;
        }
        if (!reconcileRunning.compareAndSet(false, true)) return;

        // Snapshot the immutable provider list, then keep PackageManager/LauncherApps scanning and
        // unchanged-state filtering on a low-priority worker. The UI thread receives only packages
        // whose disabled state may actually need to change; the common no-change pass posts no UI work.
        final List<AppPojo> snapshot = new ArrayList<>(getPojos());
        reconcileFuture = stateExecutor.submit(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            final boolean[] enabledStates = new boolean[snapshot.size()];
            final ArrayList<Integer> changedIndices = new ArrayList<>();
            PackageManager pm = getPackageManager();

            for (int i = 0; i < snapshot.size(); i++) {
                if (launcherScrolling || Thread.currentThread().isInterrupted()) {
                    reconcileRunning.set(false);
                    return;
                }
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
                // AppPojo state is already read from search/background workers elsewhere.
                // Pre-filter here so the main thread never performs an all-app comparison pass.
                if (pojo.isDisabled() == enabled) changedIndices.add(i);
            }

            if (changedIndices.isEmpty()) {
                reconcileRunning.set(false);
                if (!launcherScrolling && launcherUiVisible && isFrozenDetectionEnabled()) {
                    scheduleNextReconcile(reconcileDelayMs);
                }
                return;
            }
            if (launcherScrolling || Thread.currentThread().isInterrupted()) {
                reconcileRunning.set(false);
                return;
            }

            stateHandler.post(() -> {
                try {
                    if (!launcherUiVisible || launcherScrolling) return;
                    boolean changed = false;
                    for (int index : changedIndices) {
                        AppPojo pojo = snapshot.get(index);
                        boolean enabled = enabledStates[index];
                        // Re-check on the UI thread in case a LauncherApps callback updated
                        // this package while the fallback scan was still running.
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
                    if (!launcherScrolling && launcherUiVisible && isFrozenDetectionEnabled())
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
        if (!visible) launcherScrolling = false;
        AppProvider provider = activeInstance;
        if (provider != null) {
            if (!visible) {
                provider.stateHandler.removeCallbacks(provider.deferredPackageReloadRunnable);
            } else if (changed && (provider.deferredPackageReload || provider.deferredShortcutReload)) {
                provider.stateHandler.removeCallbacks(provider.deferredPackageReloadRunnable);
                provider.stateHandler.postDelayed(provider.deferredPackageReloadRunnable, HOME_RELOAD_GRACE_MS);
            }
            if (changed) provider.updateFrozenReconcileSchedule(visible);
        }
    }

    /**
     * Coalesce package/freezer state callbacks while Home is visible. The currently loaded AppPojo
     * list remains usable (including remembered frozen entries), so there is no reason to throw it
     * away during the Home transition. This also prevents AppProvider + ShortcutsProvider from
     * repeatedly cancelling/restarting each other for one package-state burst.
     */
    public static boolean deferPackageReloadWhileHomeVisible(boolean reloadShortcuts) {
        AppProvider provider = activeInstance;
        if (provider == null || !launcherUiVisible) return false;
        provider.deferredPackageReload = true;
        provider.deferredShortcutReload |= reloadShortcuts;
        provider.stateHandler.removeCallbacks(provider.deferredPackageReloadRunnable);
        provider.stateHandler.postDelayed(provider.deferredPackageReloadRunnable, HOME_RELOAD_GRACE_MS);
        return true;
    }

    public static void setLauncherScrolling(boolean scrolling) {
        launcherScrolling = scrolling;
        AppProvider provider = activeInstance;
        if (provider == null || !launcherUiVisible || !provider.isFrozenDetectionEnabled()) return;
        if (scrolling) {
            provider.stateHandler.removeCallbacks(provider.reconcileFrozenState);
            Future<?> running = provider.reconcileFuture;
            if (running != null) running.cancel(true);
            provider.reconcileFuture = null;
            provider.reconcileRunning.set(false);
        }
        // Deliberately do not schedule a reconciliation when scrolling ends. Scrolling itself must
        // not create deferred background work. LauncherApps callbacks, lifecycle and the normal
        // provider schedule remain the sources for future state refreshes.
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
        stateHandler.removeCallbacks(deferredPackageReloadRunnable);
        deferredPackageReload = false;
        deferredShortcutReload = false;
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
        return FrozenAppPreferences.detect(this);
    }

    private long getFrozenReconcileDelayMs() {
        return FrozenAppPreferences.reconcileDelayMs(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (FrozenAppPreferences.PREF_DETECT.equals(key)) {
            updateFrozenReconcileSchedule(launcherUiVisible);
            // Master detection controls both canonical apps and remembered disabled shortcuts.
            // Reload both providers so the toggle cannot leave half of the UI on stale semantics.
            reload();
            KissApplication.getApplication(this).getDataHandler().reloadShortcuts();
            KissApplication.getApplication(this).getDataHandler().refreshFavorites();
            sendBroadcast(MainActivity.internalBroadcast(this, MainActivity.LOAD_OVER));
        } else if (FrozenAppPreferences.PREF_RECONCILE_INTERVAL.equals(key)) {
            updateFrozenReconcileSchedule(launcherUiVisible);
        } else if (FrozenAppPreferences.PREF_KEEP_SEARCHABLE.equals(key)) {
            // Re-run the visible query/history boundary; provider data itself does not need reload.
            sendBroadcast(MainActivity.internalBroadcast(this, MainActivity.LOAD_OVER));
        } else if (FrozenAppPreferences.PREF_GREY.equals(key)
                || FrozenAppPreferences.PREF_KEEP_HISTORY.equals(key)) {
            // These are presentation/history policies, not provider data. Rebind History and
            // favorites without clearing icons or starting a package scan.
            KissApplication.getApplication(this).getDataHandler().refreshFavorites();
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
        List<String> semanticHints = prefs.getBoolean("semantic-search-enabled", false)
                ? SemanticHints.expand(query)
                : Collections.emptyList();

        int checked = 0;
        for (AppPojo pojo : getPojos()) {
            if ((checked++ & 31) == 0 && searcher.isCancelled()) return;
            if (pojo.isExcluded() && !prefs.getBoolean("enable-excluded-apps", false)) continue;
            if (pojo.isDisabled() && !FrozenAppPreferences.keepSearchable(this)) continue;
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
