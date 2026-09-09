from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# Exact application baseline: installed Smart S Launcher 3.30.75 commit
# 883f1f736e2224ff070e44dde493bda348bcc889

# 1. Version bump.
p = Path("app/build.gradle")
s = p.read_text()
s = replace_once(
    s,
    '        // Smart S Launcher 3.30.75 - selectable auto-scroll or full-text expanding tiles\n'
    '        versionCode 503\n'
    '        versionName "3.30.75"\n',
    '        // Smart S Launcher 3.30.76 - dormant inactive renderers and smoother Home\n'
    '        versionCode 504\n'
    '        versionName "3.30.76"\n',
    "3.30.76 version bump",
)
p.write_text(s)

# 2. Frozen-app detection keeps LauncherApps callbacks as the normal path. Periodic full-package
# scanning remains opt-in; an explicit existing 15/30/60/300 preference is preserved.
p = Path("app/src/main/java/fr/neamar/kiss/dataprovider/AppProvider.java")
s = p.read_text()
s = replace_once(
    s,
    '        String value = prefs.getString(PREF_RECONCILE_INTERVAL, "15");\n',
    '        String value = prefs.getString(PREF_RECONCILE_INTERVAL, "package-only");\n',
    "frozen fallback runtime default",
)
p.write_text(s)

p = Path("app/src/main/res/xml/preferences_smart_features.xml")
s = p.read_text()
s = replace_once(
    s,
    '        <ListPreference\n            app:defaultValue="15"\n            app:dependency="smart-detect-frozen-apps"\n            app:entries="@array/smart_frozen_refresh_entries"\n',
    '        <ListPreference\n            app:defaultValue="package-only"\n            app:dependency="smart-detect-frozen-apps"\n            app:entries="@array/smart_frozen_refresh_entries"\n',
    "frozen fallback preference default",
)
p.write_text(s)

# 3. Unread notification bell still attracts attention, but only briefly. An infinite ValueAnimator
# kept invalidating the visible Vertical List every frame for as long as an item remained unread.
p = Path("app/src/main/java/fr/neamar/kiss/ui/NotificationBellStyle.java")
s = p.read_text()
s = replace_once(
    s,
    'import android.animation.ValueAnimator;\n',
    'import android.animation.Animator;\nimport android.animation.AnimatorListenerAdapter;\nimport android.animation.ValueAnimator;\n',
    "notification animator imports",
)
old = '''    private static void startFlashing(@NonNull TextView textView, @NonNull Drawable bell) {
        ensureDetachGuard(textView);
        ValueAnimator animator = ValueAnimator.ofInt(255, 55, 255);
        animator.setDuration(900L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(value -> {
            Drawable[] current = textView.getCompoundDrawablesRelative();
            if (current.length < 3 || current[2] != bell) {
                stopFlashing(textView);
                return;
            }
            bell.setAlpha((Integer) value.getAnimatedValue());
            textView.invalidate();
        });
        FLASHERS.put(textView, animator);
        animator.start();
    }
'''
new = '''    private static void startFlashing(@NonNull TextView textView, @NonNull Drawable bell) {
        ensureDetachGuard(textView);
        ValueAnimator animator = ValueAnimator.ofInt(255, 55, 255);
        animator.setDuration(900L);
        // One repeat gives a short two-cycle attention pulse instead of a permanent per-frame loop.
        animator.setRepeatCount(1);
        animator.addUpdateListener(value -> {
            Drawable[] current = textView.getCompoundDrawablesRelative();
            if (current.length < 3 || current[2] != bell) {
                stopFlashing(textView);
                return;
            }
            bell.setAlpha((Integer) value.getAnimatedValue());
            textView.invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (FLASHERS.get(textView) == animator) FLASHERS.remove(textView);
                bell.setAlpha(255);
                textView.invalidate();
            }
        });
        FLASHERS.put(textView, animator);
        animator.start();
    }
'''
s = replace_once(s, old, new, "bounded unread bell pulse")
p.write_text(s)

# 4. Vertical Cards: no active-query/deferred frame callbacks may survive pause or inactive mode.
p = Path("app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java")
s = p.read_text()
s = replace_once(
    s,
    '    private boolean deferredRefreshIdleProbeScheduled;\n',
    '    private boolean deferredRefreshIdleProbeScheduled;\n    private boolean paused;\n',
    "smart cards paused field",
)
s = replace_once(
    s,
    '''    private final Runnable activeQueryRebuildRunnable = () -> {
        if (isEnabled() && isActiveQuery()) rebuild(true);
    };
''',
    '''    private final Runnable activeQueryRebuildRunnable = () -> {
        if (!paused && isEnabled() && isActiveQuery()) rebuild(true);
    };
''',
    "active query dormant guard",
)
s = replace_once(
    s,
    '''    private final Runnable deferredRefreshIdleProbe = () -> {
        deferredRefreshIdleProbeScheduled = false;
        if (scroller == null || !pendingDataSetRefresh || isActiveQuery()) return;
''',
    '''    private final Runnable deferredRefreshIdleProbe = () -> {
        deferredRefreshIdleProbeScheduled = false;
        if (paused || !isEnabled() || scroller == null || !pendingDataSetRefresh || isActiveQuery()) return;
''',
    "idle probe dormant guard",
)
s = replace_once(
    s,
    '''    void onResume() {
        migrateLegacySelection();
        applyState(false);
        if (isEnabled() && column != null && column.getChildCount() == 0) rebuild();
    }

    boolean onDataSetChanged() {
''',
    '''    void onResume() {
        paused = false;
        migrateLegacySelection();
        applyState(false);
        if (isEnabled() && column != null && column.getChildCount() == 0) rebuild();
    }

    void onPause() {
        paused = true;
        cancelPendingActiveQueryRebuild();
        cancelDeferredRefreshIdleProbe();
        deferredRefreshIdlePolicy.clear();
    }

    boolean onDataSetChanged() {
''',
    "smart cards pause lifecycle",
)
s = replace_once(
    s,
    '''    void onDestroy() {
        cancelPendingActiveQueryRebuild();
''',
    '''    void onDestroy() {
        paused = true;
        cancelPendingActiveQueryRebuild();
''',
    "smart cards destroy paused",
)
s = replace_once(
    s,
    '''    private void scheduleActiveQueryRebuild() {
        if (scroller == null) return;
''',
    '''    private void scheduleActiveQueryRebuild() {
        if (paused || !isEnabled() || scroller == null) return;
''',
    "active query schedule guard",
)
s = replace_once(
    s,
    '''    private void scheduleDeferredRefreshIdleProbe() {
        if (scroller == null || deferredRefreshIdleProbeScheduled || isActiveQuery()
                || !deferredRefreshIdlePolicy.shouldProbe()) return;
''',
    '''    private void scheduleDeferredRefreshIdleProbe() {
        if (paused || !isEnabled() || scroller == null || deferredRefreshIdleProbeScheduled
                || isActiveQuery() || !deferredRefreshIdlePolicy.shouldProbe()) return;
''',
    "idle probe schedule guard",
)
s = replace_once(
    s,
    '''        } else {
            scroller.setVisibility(View.GONE);
            if (HistoryDisplayForwarder.VERTICAL.equals(
''',
    '''        } else {
            cancelPendingActiveQueryRebuild();
            cancelDeferredRefreshIdleProbe();
            deferredRefreshIdlePolicy.clear();
            scroller.setVisibility(View.GONE);
            if (HistoryDisplayForwarder.VERTICAL.equals(
''',
    "inactive smart cards cleanup",
)
p.write_text(s)

# 5. Vertical Card usage/statistics work must never start for a dormant renderer.
p = Path("app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java")
s = p.read_text()
s = replace_once(
    s,
    '''    void onCreate() {
        paused = false;
        resolveColumn();
        refreshSnapshotAsync();
    }

    void onResume() {
        paused = false;
        resolveColumn();
        refreshSnapshotAsync();
    }
''',
    '''    void onCreate() {
        paused = !isEnabled();
        if (paused) return;
        resolveColumn();
        refreshSnapshotAsync();
    }

    void onResume() {
        paused = !isEnabled();
        if (paused) return;
        resolveColumn();
        refreshSnapshotAsync();
    }
''',
    "usage active lifecycle",
)
s = replace_once(
    s,
    '''    void onDataSetChanged() {
        // SmartCardListForwarder has already rebuilt the card column. Refresh the lightweight
''',
    '''    void onDataSetChanged() {
        if (destroyed || paused || !isEnabled()) return;
        // SmartCardListForwarder has already rebuilt the card column. Refresh the lightweight
''',
    "usage dataset dormant guard",
)
s = replace_once(
    s,
    '''    void onConfigurationChanged() {
        resolveColumn();
        postApplySnapshot(false, true);
    }
''',
    '''    void onConfigurationChanged() {
        if (destroyed || paused || !isEnabled()) return;
        resolveColumn();
        postApplySnapshot(false, true);
    }
''',
    "usage config dormant guard",
)
s = replace_once(
    s,
    '''    private void refreshSnapshotAsync() {
        refreshSnapshotAsync(collectShortcutTargets());
    }

    private void refreshSnapshotAsync(Map<String, String> shortcutTargets) {
        if (destroyed || !isEnabled()) {
''',
    '''    private void refreshSnapshotAsync() {
        if (destroyed || paused || !isEnabled()) return;
        refreshSnapshotAsync(collectShortcutTargets());
    }

    private void refreshSnapshotAsync(Map<String, String> shortcutTargets) {
        if (destroyed || paused || !isEnabled()) {
''',
    "usage snapshot dormant guard",
)
s = replace_once(
    s,
    '''    private void refreshLaunchStatsAsync() {
        if (destroyed || !isEnabled() || !statsRefreshInFlight.compareAndSet(false, true)) return;
''',
    '''    private void refreshLaunchStatsAsync() {
        if (destroyed || paused || !isEnabled()
                || !statsRefreshInFlight.compareAndSet(false, true)) return;
''',
    "usage launch stats dormant guard",
)
s = replace_once(
    s,
    '''            if (destroyed) {
                statsRefreshInFlight.set(false);
                return;
            }
            mainActivity.runOnUiThread(() -> {
                statsRefreshInFlight.set(false);
                if (destroyed) return;
                launchStats = freshStats;
''',
    '''            if (destroyed || paused || !isEnabled()) {
                statsRefreshInFlight.set(false);
                return;
            }
            mainActivity.runOnUiThread(() -> {
                statsRefreshInFlight.set(false);
                if (destroyed || paused || !isEnabled()) return;
                launchStats = freshStats;
''',
    "usage launch stats completion guard",
)
p.write_text(s)

print("3.30.76 core dormancy patch applied with exact 3.30.75 assertions")
