from pathlib import Path


def replace_once(path, old, new, label):
    p = Path(path)
    s = p.read_text()
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    p.write_text(s.replace(old, new, 1))


# Version and build workflow.
replace_once('app/build.gradle', '// Smart S Launcher 3.30.07', '// Smart S Launcher 3.30.08', 'version comment')
replace_once('app/build.gradle', 'versionCode 435', 'versionCode 436', 'version code')
replace_once('app/build.gradle', 'versionName "3.30.07"', 'versionName "3.30.08"', 'version name')

build = Path('.github/workflows/build.yml')
s = build.read_text()
for old, new in [
    ('versionCode 435', 'versionCode 436'),
    ('versionName \\"3.30.07\\"', 'versionName \\"3.30.08\\"'),
    ("versionCode='435'", "versionCode='436'"),
    ("versionName='3.30.07'", "versionName='3.30.08'"),
    ('smart-s-launcher-3.30.07-debug', 'smart-s-launcher-3.30.08-debug'),
]:
    if old not in s:
        raise SystemExit(f'build workflow token missing: {old}')
    s = s.replace(old, new)
build.write_text(s)

# LoadAppPojos: make the visible Smart preference the source of truth and only restore
# hidden/frozen catalog entries when detection is enabled.
path = 'app/src/main/java/fr/neamar/kiss/loader/LoadAppPojos.java'
replace_once(path,
    '    public static final String PREF_INDEX_DISABLED_APPS = "index-disabled-apps";\n',
    '    public static final String PREF_INDEX_DISABLED_APPS = "index-disabled-apps";\n'
    '    public static final String PREF_DETECT_FROZEN_APPS = "smart-detect-frozen-apps";\n',
    'frozen detect constant')
replace_once(path,
    '        boolean indexDisabledApps = prefs.getBoolean(PREF_INDEX_DISABLED_APPS, true);\n',
    '        boolean indexDisabledApps = prefs.getBoolean(PREF_DETECT_FROZEN_APPS,\n'
    '                prefs.getBoolean(PREF_INDEX_DISABLED_APPS, true));\n',
    'frozen detect preference')
replace_once(path,
    '                boolean disabled = PackageManagerUtils.isAppSuspended(appInfo) || isQuietModeEnabled(manager, profile);\n',
    '                boolean disabled = indexDisabledApps\n'
    '                        && (PackageManagerUtils.isAppSuspended(appInfo) || isQuietModeEnabled(manager, profile));\n',
    'profile disabled gate')
replace_once(path,
    '        int flags = PackageManager.MATCH_DISABLED_COMPONENTS;\n',
    '        int flags = indexDisabledApps ? PackageManager.MATCH_DISABLED_COMPONENTS : 0;\n',
    'disabled query flags')

p = Path(path)
s = p.read_text()
start_marker = '        // Persistent catalog is the final safety net. IceBox can hide a disabled package from both\n'
end_marker = '        Map<String, AppRecord> customApps = DBHelper.getCustomAppData(ctx);\n'
start = s.find(start_marker)
end = s.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('persistent catalog block markers not found')
block = s[start:end]
if 'if (indexDisabledApps)' in block:
    raise SystemExit('persistent catalog block already gated unexpectedly')
indented = ''.join(('    ' + line if line.strip() else line) for line in block.splitlines(True))
wrapped = '        if (indexDisabledApps) {\n' + indented + '        }\n\n'
s = s[:start] + wrapped + s[end:]
p.write_text(s)

# AppProvider: wire package monitoring, searchability and the selected reconciliation cadence.
path = 'app/src/main/java/fr/neamar/kiss/dataprovider/AppProvider.java'
replace_once(path,
    'public class AppProvider extends Provider<AppPojo> {\n',
    'public class AppProvider extends Provider<AppPojo>\n'
    '        implements SharedPreferences.OnSharedPreferenceChangeListener {\n',
    'AppProvider preference listener')
replace_once(path,
    '    private static final long FROZEN_RECONCILE_INITIAL_DELAY_MS = 2500L;\n'
    '    private static final long FROZEN_RECONCILE_MS = 60000L;\n',
    '    private static final long FROZEN_RECONCILE_INITIAL_DELAY_MS = 2500L;\n'
    '    private static final String PREF_DETECT_FROZEN = "smart-detect-frozen-apps";\n'
    '    private static final String PREF_KEEP_FROZEN_SEARCHABLE = "smart-keep-frozen-searchable";\n'
    '    private static final String PREF_PACKAGE_MONITORING = "smart-package-change-monitoring";\n'
    '    private static final String PREF_RECONCILE_INTERVAL = "smart-frozen-refresh-interval";\n',
    'AppProvider frozen constants')
replace_once(path,
    '    private LauncherApps launcherApps;\n',
    '    private LauncherApps launcherApps;\n'
    '    private SharedPreferences prefs;\n',
    'AppProvider prefs field')
replace_once(path,
    '        private void handleEvent(String action, String[] packageNames,\n'
    '                                 android.os.UserHandle user, boolean replacing) {\n'
    '            PackageAddedRemovedHandler.handleEvent(AppProvider.this, action, packageNames,\n'
    '                    new UserHandle(AppProvider.this, user), replacing);\n'
    '        }\n',
    '        private void handleEvent(String action, String[] packageNames,\n'
    '                                 android.os.UserHandle user, boolean replacing) {\n'
    '            if (prefs != null && !prefs.getBoolean(PREF_PACKAGE_MONITORING, true)) return;\n'
    '            PackageAddedRemovedHandler.handleEvent(AppProvider.this, action, packageNames,\n'
    '                    new UserHandle(AppProvider.this, user), replacing);\n'
    '        }\n',
    'package monitoring gate')
replace_once(path,
    '    private final Runnable reconcileFrozenState = () -> {\n'
    '        if (!launcherUiVisible) return;\n'
    '        if (!isLoaded()) {\n'
    '            scheduleNextReconcile(FROZEN_RECONCILE_INITIAL_DELAY_MS);\n'
    '            return;\n'
    '        }\n',
    '    private final Runnable reconcileFrozenState = () -> {\n'
    '        if (!launcherUiVisible || !isFrozenDetectionEnabled()) return;\n'
    '        long reconcileDelayMs = getFrozenReconcileDelayMs();\n'
    '        if (reconcileDelayMs < 0L) return;\n'
    '        if (!isLoaded()) {\n'
    '            scheduleNextReconcile(Math.min(FROZEN_RECONCILE_INITIAL_DELAY_MS, reconcileDelayMs));\n'
    '            return;\n'
    '        }\n',
    'reconcile start gate')
replace_once(path,
    '                    if (launcherUiVisible) scheduleNextReconcile(FROZEN_RECONCILE_MS);\n',
    '                    if (launcherUiVisible && isFrozenDetectionEnabled())\n'
    '                        scheduleNextReconcile(reconcileDelayMs);\n',
    'reconcile cadence')
replace_once(path,
    '    public void onCreate() {\n'
    '        activeInstance = this;\n'
    '        launcherApps = ContextCompat.getSystemService(this, LauncherApps.class);\n',
    '    public void onCreate() {\n'
    '        activeInstance = this;\n'
    '        prefs = PreferenceManager.getDefaultSharedPreferences(this);\n'
    '        prefs.registerOnSharedPreferenceChangeListener(this);\n'
    '        launcherApps = ContextCompat.getSystemService(this, LauncherApps.class);\n',
    'provider preference registration')
replace_once(path,
    '    @Override public void onDestroy() {\n'
    '        stateHandler.removeCallbacks(reconcileFrozenState);\n',
    '    @Override public void onDestroy() {\n'
    '        stateHandler.removeCallbacks(reconcileFrozenState);\n'
    '        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);\n',
    'provider preference unregister')
replace_once(path,
    '    private void updateFrozenReconcileSchedule(boolean visible) {\n'
    '        stateHandler.removeCallbacks(reconcileFrozenState);\n'
    '        if (visible) scheduleNextReconcile(FROZEN_RECONCILE_INITIAL_DELAY_MS);\n'
    '    }\n\n'
    '    private void scheduleNextReconcile(long delayMs) {\n',
    '    private boolean isFrozenDetectionEnabled() {\n'
    '        return prefs == null || prefs.getBoolean(PREF_DETECT_FROZEN, true);\n'
    '    }\n\n'
    '    private long getFrozenReconcileDelayMs() {\n'
    '        if (prefs == null) return -1L;\n'
    '        String value = prefs.getString(PREF_RECONCILE_INTERVAL, "15");\n'
    '        if (value == null || "package-only".equals(value)) return -1L;\n'
    '        try {\n'
    '            long seconds = Long.parseLong(value);\n'
    '            return Math.max(15L, Math.min(300L, seconds)) * 1000L;\n'
    '        } catch (NumberFormatException ignored) {\n'
    '            return -1L;\n'
    '        }\n'
    '    }\n\n'
    '    @Override\n'
    '    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {\n'
    '        if (PREF_DETECT_FROZEN.equals(key)) {\n'
    '            updateFrozenReconcileSchedule(launcherUiVisible);\n'
    '            reload();\n'
    '        } else if (PREF_RECONCILE_INTERVAL.equals(key)) {\n'
    '            updateFrozenReconcileSchedule(launcherUiVisible);\n'
    '        } else if (PREF_KEEP_FROZEN_SEARCHABLE.equals(key)) {\n'
    '            sendBroadcast(MainActivity.internalBroadcast(this, MainActivity.LOAD_OVER));\n'
    '        }\n'
    '    }\n\n'
    '    private void updateFrozenReconcileSchedule(boolean visible) {\n'
    '        stateHandler.removeCallbacks(reconcileFrozenState);\n'
    '        if (!visible || !isFrozenDetectionEnabled()) return;\n'
    '        long delayMs = getFrozenReconcileDelayMs();\n'
    '        if (delayMs >= 0L) scheduleNextReconcile(Math.min(FROZEN_RECONCILE_INITIAL_DELAY_MS, delayMs));\n'
    '    }\n\n'
    '    private void scheduleNextReconcile(long delayMs) {\n',
    'reconcile helpers')
replace_once(path,
    '        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);\n'
    '        List<String> semanticHints = SemanticHints.expand(query);\n',
    '        List<String> semanticHints = SemanticHints.expand(query);\n',
    'request prefs allocation')
replace_once(path,
    '            if (pojo.isExcluded() && !prefs.getBoolean("enable-excluded-apps", false)) continue;\n'
    '            if (excludedFavoriteIds.contains(pojo.getFavoriteId())) continue;\n',
    '            if (pojo.isExcluded() && !prefs.getBoolean("enable-excluded-apps", false)) continue;\n'
    '            if (pojo.isDisabled()\n'
    '                    && (!isFrozenDetectionEnabled()\n'
    '                    || !prefs.getBoolean(PREF_KEEP_FROZEN_SEARCHABLE, true))) continue;\n'
    '            if (excludedFavoriteIds.contains(pojo.getFavoriteId())) continue;\n',
    'frozen searchability gate')

# AppResult: remove PackageManager state checks from hot rendering/icon paths, honor grey/auto-enable switches.
path = 'app/src/main/java/fr/neamar/kiss/result/AppResult.java'
replace_once(path,
    'import androidx.core.content.ContextCompat;\n',
    'import androidx.core.content.ContextCompat;\nimport androidx.preference.PreferenceManager;\n',
    'AppResult preference import')
replace_once(path,
    '        boolean wasDisabled = pojo.isDisabled();\n'
    '        boolean disabledNow = refreshLiveDisabledState(context);\n'
    '        if (wasDisabled != disabledNow) clearIcon();\n\n',
    '',
    'remove row bind state query')
replace_once(path,
    '        refreshLiveDisabledState(context);\n'
    '        restoreWarmIcon(context);\n',
    '        restoreWarmIcon(context);\n',
    'remove favorite state query')
replace_once(path,
    '        if (pojo.isDisabled()) imageView.setColorFilter(FROZEN_ICON_FILTER);\n'
    '        else imageView.clearColorFilter();\n',
    '        boolean greyFrozen = PreferenceManager.getDefaultSharedPreferences(imageView.getContext())\n'
    '                .getBoolean("smart-grey-frozen-apps", true);\n'
    '        if (pojo.isDisabled() && greyFrozen) imageView.setColorFilter(FROZEN_ICON_FILTER);\n'
    '        else imageView.clearColorFilter();\n',
    'grey preference gate')
replace_once(path,
    '    private boolean refreshLiveDisabledState(Context context) {\n'
    '        // PackageManager can still see IceBox-disabled packages even when LauncherApps hides them.\n'
    '        boolean enabled = AppLaunchUtils.isPackageEnabled(context, pojo.packageName);\n'
    '        pojo.setDisabled(!enabled);\n'
    '        return !enabled;\n'
    '    }\n',
    '    private boolean refreshLiveDisabledState(Context context) {\n'
    '        if (!PreferenceManager.getDefaultSharedPreferences(context)\n'
    '                .getBoolean("smart-detect-frozen-apps", true)) {\n'
    '            pojo.setDisabled(false);\n'
    '            return false;\n'
    '        }\n'
    '        // Live verification is reserved for user actions; rendering trusts provider state.\n'
    '        boolean enabled = AppLaunchUtils.isPackageEnabled(context, pojo.packageName);\n'
    '        pojo.setDisabled(!enabled);\n'
    '        return !enabled;\n'
    '    }\n',
    'live state detect gate')
replace_once(path,
    '    public Drawable getDrawable(Context context) {\n'
    '        refreshLiveDisabledState(context);\n'
    '        if (icon == null) {\n',
    '    public Drawable getDrawable(Context context) {\n'
    '        if (icon == null) {\n',
    'remove drawable state query')
replace_once(path,
    '        if (wasFrozen) {\n'
    '            if (!pojo.userHandle.isCurrentUser()) {\n',
    '        if (wasFrozen) {\n'
    '            if (!PreferenceManager.getDefaultSharedPreferences(context)\n'
    '                    .getBoolean("smart-auto-enable-frozen-apps", true)) {\n'
    '                Toast.makeText(context, "App is frozen. Auto-enable frozen apps is off.",\n'
    '                        Toast.LENGTH_LONG).show();\n'
    '                return;\n'
    '            }\n'
    '            if (!pojo.userHandle.isCurrentUser()) {\n',
    'auto-enable preference gate')

# DataHandler: make the history/favorites switch reversible (filter only, never delete persisted data)
# and reload app state when frozen detection changes.
path = 'app/src/main/java/fr/neamar/kiss/DataHandler.java'
replace_once(path,
    '    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {\n'
    '        if (key != null && key.startsWith("enable-")) {\n',
    '    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {\n'
    '        if ("smart-detect-frozen-apps".equals(key)) {\n'
    '            reloadApps();\n'
    '        }\n'
    '        if (key != null && key.startsWith("enable-")) {\n',
    'DataHandler detect reload')
replace_once(path,
    '        // Find associated items\n'
    '        int size = ids.size();\n',
    '        // Find associated items\n'
    '        boolean keepFrozenHistory = PreferenceManager.getDefaultSharedPreferences(context)\n'
    '                .getBoolean("smart-keep-frozen-history", true);\n'
    '        int size = ids.size();\n',
    'history frozen preference')
replace_once(path,
    '            if (pojo == null) {\n'
    '                continue;\n'
    '            }\n\n'
    '            if (itemsToExcludeById.contains(pojo.id)) {\n',
    '            if (pojo == null) {\n'
    '                continue;\n'
    '            }\n'
    '            if (!keepFrozenHistory && pojo instanceof AppPojo && ((AppPojo) pojo).isDisabled()) {\n'
    '                continue;\n'
    '            }\n\n'
    '            if (itemsToExcludeById.contains(pojo.id)) {\n',
    'history frozen filter')
replace_once(path,
    '    public List<Pojo> getFavorites() {\n'
    '        List<String> favoriteIds = getFavoriteIds();\n'
    '        List<Pojo> favorites = new ArrayList<>(favoriteIds.size());\n',
    '    public List<Pojo> getFavorites() {\n'
    '        List<String> favoriteIds = getFavoriteIds();\n'
    '        List<Pojo> favorites = new ArrayList<>(favoriteIds.size());\n'
    '        boolean keepFrozenHistory = PreferenceManager.getDefaultSharedPreferences(context)\n'
    '                .getBoolean("smart-keep-frozen-history", true);\n',
    'favorites frozen preference')
replace_once(path,
    '            Pojo pojo = getPojo(favoriteIds.get(i));\n'
    '            if (pojo != null) {\n'
    '                favorites.add(pojo);\n'
    '            }\n',
    '            Pojo pojo = getPojo(favoriteIds.get(i));\n'
    '            if (pojo != null\n'
    '                    && (keepFrozenHistory || !(pojo instanceof AppPojo)\n'
    '                    || !((AppPojo) pojo).isDisabled())) {\n'
    '                favorites.add(pojo);\n'
    '            }\n',
    'favorites frozen filter')

# Final invariants: no accidental legacy-only wiring in the loader; requested Smart keys appear in runtime code.
checks = {
    'app/src/main/java/fr/neamar/kiss/loader/LoadAppPojos.java': ['PREF_DETECT_FROZEN_APPS', 'if (indexDisabledApps) {'],
    'app/src/main/java/fr/neamar/kiss/dataprovider/AppProvider.java': ['PREF_PACKAGE_MONITORING', 'getFrozenReconcileDelayMs()', 'PREF_KEEP_FROZEN_SEARCHABLE'],
    'app/src/main/java/fr/neamar/kiss/result/AppResult.java': ['smart-grey-frozen-apps', 'smart-auto-enable-frozen-apps'],
    'app/src/main/java/fr/neamar/kiss/DataHandler.java': ['smart-keep-frozen-history'],
}
for file, tokens in checks.items():
    text = Path(file).read_text()
    for token in tokens:
        if token not in text:
            raise SystemExit(f'{file}: missing invariant {token}')
