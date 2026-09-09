from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# 1. Shared resize UI remains available for every history style, but the Vertical Cards column
# observer exists only when Vertical Cards is selected and while Home is resumed.
p = Path("app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardGroupResizeController.java")
s = p.read_text()
s = replace_once(
    s,
    '''    void onPause() {
        dismissDialog();
    }
''',
    '''    void onPause() {
        dismissDialog();
        detachObserver();
        cancelPendingHostWidthApply();
    }
''',
    "resize pause cleanup",
)
s = replace_once(
    s,
    '''    private void attachObserver() {
        if (column == null || layoutListener != null) return;
        layoutListener = this::applyWidthToAllCards;
''',
    '''    private void attachObserver() {
        if (!isVerticalCards()) {
            detachObserver();
            return;
        }
        if (column == null || layoutListener != null) return;
        layoutListener = this::applyWidthToAllCards;
''',
    "resize observer active-style guard",
)
p.write_text(s)

# 2. Vertical Cards viewport listeners detach whenever this renderer is inactive/backgrounded.
p = Path("app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardViewportController.java")
s = p.read_text()
s = replace_once(
    s,
    '''    void onLauncherResumed() {
        resumed = true;
        ensureSavedReturnSnapshotLoaded();
        requestSavedReturnRestore();
        scheduleLatestCardControlsUpdate();
        if (pendingNotificationAttention) startLatestAttentionPulse();
    }
''',
    '''    void onLauncherResumed() {
        resumed = true;
        resolveViews();
        ensureSavedReturnSnapshotLoaded();
        requestSavedReturnRestore();
        scheduleLatestCardControlsUpdate();
        if (pendingNotificationAttention) startLatestAttentionPulse();
    }

    void onPause() {
        resumed = false;
        generation++;
        bottomPassScheduled = false;
        latestControlsUpdateScheduled = false;
        stopLatestAttentionPulse();
        setLatestCardControlsVisible(false);
        detachViewportListeners();
    }
''',
    "viewport resume pause lifecycle",
)
old = '''    private void resolveViews() {
        if (destroyed) return;
        FrameLayout nextHost = mainActivity.listContainer instanceof FrameLayout
                ? (FrameLayout) mainActivity.listContainer : null;
        if (nextHost != host) {
            removeLatestCardControls();
            host = nextHost;
            installLatestCardControls();
        }

        ScrollView nextScroller = smartCardListForwarder.getScroller();
        ViewGroup nextColumn = smartCardListForwarder.getColumn();
        if (nextScroller == scroller && nextColumn == column) {
            scheduleLatestCardControlsUpdate();
            return;
        }

        detachViewportListeners();
        scroller = nextScroller;
        column = nextColumn;
        if (scroller != null) scroller.addOnLayoutChangeListener(geometryListener);
        if (column != null) column.addOnLayoutChangeListener(geometryListener);
        if (scroller != null) {
            scroller.getViewTreeObserver().addOnScrollChangedListener(scrollChangedListener);
        }
        scheduleLatestCardControlsUpdate();
    }
'''
new = '''    private void resolveViews() {
        if (destroyed) return;
        FrameLayout nextHost = mainActivity.listContainer instanceof FrameLayout
                ? (FrameLayout) mainActivity.listContainer : null;
        if (nextHost != host) {
            removeLatestCardControls();
            host = nextHost;
            installLatestCardControls();
        }

        ScrollView nextScroller = smartCardListForwarder.getScroller();
        ViewGroup nextColumn = smartCardListForwarder.getColumn();
        if (!isEnabled()) {
            detachViewportListeners();
            scroller = nextScroller;
            column = nextColumn;
            setLatestCardControlsVisible(false);
            return;
        }
        if (nextScroller == scroller && nextColumn == column) {
            scheduleLatestCardControlsUpdate();
            return;
        }

        detachViewportListeners();
        scroller = nextScroller;
        column = nextColumn;
        if (scroller != null) scroller.addOnLayoutChangeListener(geometryListener);
        if (column != null) column.addOnLayoutChangeListener(geometryListener);
        if (scroller != null) {
            scroller.getViewTreeObserver().addOnScrollChangedListener(scrollChangedListener);
        }
        scheduleLatestCardControlsUpdate();
    }
'''
s = replace_once(s, old, new, "viewport active-style listener guard")
s = replace_once(
    s,
    '''    private void scheduleBottomPass() {
        resolveViews();
        if (bottomPassScheduled || !canControlViewport() || !policy.shouldPinGeometry()) return;
''',
    '''    private void scheduleBottomPass() {
        resolveViews();
        if (!resumed || bottomPassScheduled || !canControlViewport() || !policy.shouldPinGeometry()) return;
''',
    "viewport bottom pass pause guard",
)
s = replace_once(
    s,
    '''    private void scheduleLatestCardControlsUpdate() {
        if (destroyed || latestControlsUpdateScheduled) return;
''',
    '''    private void scheduleLatestCardControlsUpdate() {
        if (destroyed || !resumed || latestControlsUpdateScheduled) return;
''',
    "viewport controls pause guard",
)
s = replace_once(
    s,
    '''    private boolean isCurrent(int token, ScrollView target) {
        return token == generation && target == scroller && canControlViewport();
    }
''',
    '''    private boolean isCurrent(int token, ScrollView target) {
        return resumed && token == generation && target == scroller && canControlViewport();
    }
''',
    "viewport restore pause guard",
)
p.write_text(s)

# 3. Square-U stability: global layout and touch listeners only exist while Square-U is active.
p = Path("app/src/main/java/fr/neamar/kiss/forwarder/SquareUStabilityController.java")
s = p.read_text()
s = replace_once(
    s,
    '    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;\n',
    '    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;\n    private final Runnable refreshRunnable = this::applyStableGeometry;\n',
    "square stability refresh runnable",
)
s = replace_once(
    s,
    '''    void onCreate() {
        resolveViews();
        attachObserver();
        installPinchResize();
        refreshSoon();
    }

    void onResume() {
        readBounds();
        resolveViews();
        attachObserver();
        installPinchResize();
        refreshSoon();
    }

    void onPause() {
        scalingGesture = false;
        finishResizeMode(true);
    }

    void onDataSetChanged() {
        resolveViews();
        attachObserver();
        installPinchResize();
        refreshSoon();
    }

    void onConfigurationChanged() {
        finishResizeMode(true);
        resolveViews();
        installPinchResize();
        refreshSoon();
    }
''',
    '''    void onCreate() {
        resolveViews();
        if (!isUStyle()) return;
        attachObserver();
        installPinchResize();
        refreshSoon();
    }

    void onResume() {
        readBounds();
        resolveViews();
        if (!isUStyle()) {
            onPause();
            return;
        }
        attachObserver();
        installPinchResize();
        refreshSoon();
    }

    void onPause() {
        scalingGesture = false;
        finishResizeMode(true);
        detachObserver();
        if (squareTrack != null) {
            squareTrack.removeCallbacks(refreshRunnable);
            squareTrack.setOnTouchListener(null);
        }
    }

    void onDataSetChanged() {
        resolveViews();
        if (!isUStyle()) {
            onPause();
            return;
        }
        attachObserver();
        installPinchResize();
        refreshSoon();
    }

    void onConfigurationChanged() {
        finishResizeMode(true);
        resolveViews();
        if (!isUStyle()) {
            onPause();
            return;
        }
        installPinchResize();
        attachObserver();
        refreshSoon();
    }
''',
    "square stability active lifecycle",
)
s = replace_once(
    s,
    '''    private void attachObserver() {
        if (squareTrack == null || layoutListener != null) return;
''',
    '''    private void attachObserver() {
        if (!isUStyle() || squareTrack == null || layoutListener != null) return;
''',
    "square stability observer guard",
)
s = replace_once(
    s,
    '''    private void installPinchResize() {
        if (squareTrack == null) return;
''',
    '''    private void installPinchResize() {
        if (!isUStyle() || squareTrack == null) return;
''',
    "square stability touch guard",
)
s = replace_once(
    s,
    '''    private void refreshSoon() {
        if (squareTrack != null) squareTrack.post(this::applyStableGeometry);
    }
''',
    '''    private void refreshSoon() {
        if (!isUStyle() || squareTrack == null) return;
        squareTrack.removeCallbacks(refreshRunnable);
        squareTrack.post(refreshRunnable);
    }
''',
    "square stability coalesced refresh",
)
p.write_text(s)

# 4. Square-U edge mapper: detach global layout observer and queued work while inactive/paused.
p = Path("app/src/main/java/fr/neamar/kiss/forwarder/SquareUEdgeBoundsController.java")
s = p.read_text()
s = replace_once(
    s,
    '    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;\n',
    '    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;\n    private final Runnable refreshRunnable = this::applyEdgeMapping;\n',
    "square edge refresh runnable",
)
s = replace_once(
    s,
    '''    void onCreate() {
        resolveTrack();
        attachObserver();
        refreshSoon();
    }

    void onResume() {
        resolveTrack();
        attachObserver();
        refreshSoon();
    }

    void onDataSetChanged() {
        resolveTrack();
        attachObserver();
        refreshSoon();
    }
''',
    '''    void onCreate() {
        resolveTrack();
        if (!isUStyle()) return;
        attachObserver();
        refreshSoon();
    }

    void onResume() {
        resolveTrack();
        if (!isUStyle()) {
            onPause();
            return;
        }
        attachObserver();
        refreshSoon();
    }

    void onDataSetChanged() {
        resolveTrack();
        if (!isUStyle()) {
            onPause();
            return;
        }
        attachObserver();
        refreshSoon();
    }
''',
    "square edge active lifecycle",
)
s = replace_once(
    s,
    '''    void onPause() {
        // No animation or polling is owned here.
    }
''',
    '''    void onPause() {
        detachObserver();
        if (squareTrack != null) squareTrack.removeCallbacks(refreshRunnable);
    }
''',
    "square edge pause cleanup",
)
s = replace_once(
    s,
    '''    private void attachObserver() {
        if (squareTrack == null || layoutListener != null) return;
''',
    '''    private void attachObserver() {
        if (!isUStyle() || squareTrack == null || layoutListener != null) return;
''',
    "square edge observer guard",
)
s = replace_once(
    s,
    '''    private void refreshSoon() {
        if (squareTrack != null) squareTrack.post(this::applyEdgeMapping);
    }
''',
    '''    private void refreshSoon() {
        if (!isUStyle() || squareTrack == null) return;
        squareTrack.removeCallbacks(refreshRunnable);
        squareTrack.post(refreshRunnable);
    }
''',
    "square edge coalesced refresh",
)
p.write_text(s)

# 5. HistoryVisualEnhancer may enrich only the selected renderer. Its async Vertical List
# LaunchStats/UsageStats load is cancelled logically when Home pauses or the mode changes.
p = Path("app/src/main/java/fr/neamar/kiss/forwarder/HistoryVisualEnhancer.java")
s = p.read_text()
s = replace_once(
    s,
    '''    private final Set<String> liveLoadInFlight = new HashSet<>();
    private boolean statsLoadInFlight;
''',
    '''    private final Set<String> liveLoadInFlight = new HashSet<>();
    private final Runnable refreshRunnable = this::refreshNow;
    private boolean statsLoadInFlight;
    private boolean paused = true;
    private boolean pendingRefresh = true;
    private int generation;
''',
    "visual enhancer lifecycle fields",
)
s = replace_once(
    s,
    '''    void onCreate() {
        refreshSoon();
    }

    void onResume() {
        refreshSoon();
    }

    void onDataSetChanged() {
        refreshSoon();
    }

    private void refreshSoon() {
        View anchor = activity.listContainer;
        if (anchor != null) anchor.post(this::refreshNow);
    }

    private void refreshNow() {
        ViewGroup squareTrack = readField("squareTrack", ViewGroup.class);
        LinearLayout horizontalRow = readField("row", LinearLayout.class);
        ScrollView notificationScroller = readField("notificationScroller", ScrollView.class);

        enhanceHistoryGroup(squareTrack, true);
        enhanceHistoryGroup(horizontalRow, false);
        refreshListPresentation();

        if (notificationScroller != null) {
            notificationScroller.setBackground(buildDepthBackground(dp(20), true));
            notificationScroller.setElevation(dp(12));
            notificationScroller.setTranslationZ(dp(2));
        }
    }
''',
    '''    void onCreate() {
        pendingRefresh = true;
    }

    void onResume() {
        paused = false;
        if (pendingRefresh) refreshSoon();
    }

    void onPause() {
        paused = true;
        generation++;
        pendingRefresh = true;
        View anchor = activity.listContainer;
        if (anchor != null) anchor.removeCallbacks(refreshRunnable);
    }

    void onDataSetChanged() {
        if (paused) {
            pendingRefresh = true;
            return;
        }
        refreshSoon();
    }

    private void refreshSoon() {
        if (paused) {
            pendingRefresh = true;
            return;
        }
        View anchor = activity.listContainer;
        if (anchor == null) return;
        pendingRefresh = true;
        anchor.removeCallbacks(refreshRunnable);
        anchor.post(refreshRunnable);
    }

    private void refreshNow() {
        if (paused) {
            pendingRefresh = true;
            return;
        }
        pendingRefresh = false;
        String layout = activeLayout();
        if (HistoryDisplayForwarder.VERTICAL.equals(layout)) {
            refreshListPresentation();
            return;
        }
        if (HistoryDisplayForwarder.SQUARE_U.equals(layout)) {
            ViewGroup squareTrack = readField("squareTrack", ViewGroup.class);
            ScrollView notificationScroller = readField("notificationScroller", ScrollView.class);
            enhanceHistoryGroup(squareTrack, true);
            if (notificationScroller != null) {
                notificationScroller.setBackground(buildDepthBackground(dp(20), true));
                notificationScroller.setElevation(dp(12));
                notificationScroller.setTranslationZ(dp(2));
            }
            return;
        }
        if (HistoryDisplayForwarder.ICONS.equals(layout)
                || HistoryDisplayForwarder.CARDS.equals(layout)
                || HistoryDisplayForwarder.NAMES.equals(layout)) {
            LinearLayout horizontalRow = readField("row", LinearLayout.class);
            enhanceHistoryGroup(horizontalRow, false);
        }
        // Wheel and Vertical Cards own their own render/enrichment pipelines.
    }

    private String activeLayout() {
        String layout = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                .getString(HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL);
        return layout == null ? HistoryDisplayForwarder.VERTICAL : layout;
    }
''',
    "visual enhancer mode-aware lifecycle",
)
s = replace_once(
    s,
    '''    private void refreshListPresentation() {
        if (activity.list == null || activity.adapter == null) return;
''',
    '''    private void refreshListPresentation() {
        if (paused || !HistoryDisplayForwarder.VERTICAL.equals(activeLayout())
                || activity.list == null || activity.adapter == null) return;
''',
    "native list stats active guard",
)
s = replace_once(
    s,
    '''        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            Map<String, LaunchStatsProvider.LaunchStats> stats =
                    LaunchStatsProvider.loadAll(activity.getApplicationContext());
            AppUsageTodayStore.Snapshot usage =
                    AppUsageTodayStore.getToday(activity.getApplicationContext());
            activity.runOnUiThread(() -> {
                synchronized (HistoryVisualEnhancer.this) {
                    statsLoadInFlight = false;
                }
                applyStatsToVisibleRows(stats, usage);
            });
        });
''',
    '''        final int token = generation;
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            Map<String, LaunchStatsProvider.LaunchStats> stats =
                    LaunchStatsProvider.loadAll(activity.getApplicationContext());
            AppUsageTodayStore.Snapshot usage =
                    AppUsageTodayStore.getToday(activity.getApplicationContext());
            activity.runOnUiThread(() -> {
                synchronized (HistoryVisualEnhancer.this) {
                    statsLoadInFlight = false;
                }
                if (paused || token != generation
                        || !HistoryDisplayForwarder.VERTICAL.equals(activeLayout())) {
                    pendingRefresh = true;
                    return;
                }
                applyStatsToVisibleRows(stats, usage);
            });
        });
''',
    "native list stats generation guard",
)
s = replace_once(
    s,
    '''                if (!tile.isAttachedToWindow() || data == null) return;
''',
    '''                if (paused || !tile.isAttachedToWindow() || !tile.isShown() || data == null) return;
''',
    "live tile stale completion guard",
)
s = replace_once(
    s,
    '''            activity.runOnUiThread(() -> {
                if (!tile.isAttachedToWindow()) return;
                addArtworkBackground(tile, artwork);
''',
    '''            activity.runOnUiThread(() -> {
                if (paused || !tile.isAttachedToWindow() || !tile.isShown()) return;
                addArtworkBackground(tile, artwork);
''',
    "shortcut artwork stale completion guard",
)
p.write_text(s)

# 6. HistoryDisplayForwarder can synchronize a Settings mode change without rebuilding the same
# renderer on every ordinary Home return.
p = Path("app/src/main/java/fr/neamar/kiss/forwarder/HistoryDisplayForwarder.java")
s = p.read_text()
s = replace_once(
    s,
    '''    void onResume() {
        applyMode(false);
        if (WHEEL_3D.equals(activeMode)) {
''',
    '''    void onResume() {
        applyMode(false);
        if (WHEEL_3D.equals(activeMode)) {
''',
    "history resume baseline assertion",
)
anchor = '''    void onDataSetChanged() {
        if (!VERTICAL.equals(activeMode) && !VERTICAL_CARDS.equals(activeMode)) rebuild();
    }
'''
insert = anchor + '''
    void onResumeIfModeChanged() {
        String requested = prefs.getString(PREF_LAYOUT, VERTICAL);
        if (requested == null) requested = VERTICAL;
        if (requested.equals(activeMode)) return;
        onResume();
    }
'''
s = replace_once(s, anchor, insert, "history mode-change-only resume")
p.write_text(s)

# 7. Manager now pairs every active-style resume with a pause, and resumes style-specific
# controllers after the first lifecycle too. Activity Launcher is intentionally untouched.
p = Path("app/src/main/java/fr/neamar/kiss/forwarder/ForwarderManager.java")
s = p.read_text()
s = replace_once(
    s,
    '''        if (initialResumeComplete) {
            verticalCardGroupResizeController.onResume();
            if (verticalCards) {
                verticalCardNotificationHistoryForwarder.onResume();
                verticalCardUsageForwarder.onResume();
            }
            return;
        }
''',
    '''        if (initialResumeComplete) {
            historyDisplayForwarder.onResumeIfModeChanged();
            verticalCardGroupResizeController.onResume();
            if (verticalCards) {
                smartCardListForwarder.onResume();
                verticalMapsCardForwarder.onResume();
                verticalCardNotificationHistoryForwarder.onResume();
                verticalCardUsageForwarder.onResume();
            } else if (square) {
                squareUHostFullscreenController.onResume();
                squareUStabilityController.onResume();
                squareUEdgeBoundsController.onResume();
                uNotificationHistoryLongPressForwarder.onResume();
            }
            if (isHistorySearch() && !verticalCards) historyVisualEnhancer.onResume();
            return;
        }
''',
    "manager subsequent style resume",
)
s = replace_once(
    s,
    '''    public void onPause() {
        if (isVerticalCardsMode()) {
            // Capture only when Vertical Cards actually owns the visible history viewport.
            verticalCardViewportController.onLauncherPaused();
            // Stop Vertical Cards UI mutation/attention work before Android's external transition.
            verticalCardNotificationHistoryForwarder.onPause();
            verticalCardUsageForwarder.onPause();
        }
        experienceTweaks.onPause();
        notificationForwarder.onPause();
    }
''',
    '''    public void onPause() {
        historyVisualEnhancer.onPause();
        verticalCardGroupResizeController.onPause();
        if (isVerticalCardsMode()) {
            // Capture while the visible card tree still owns its exact viewport, then quiesce all
            // renderer callbacks/listeners before Android completes the external transition.
            verticalCardViewportController.onLauncherPaused();
            smartCardListForwarder.onPause();
            verticalMapsCardForwarder.onPause();
            verticalCardNotificationHistoryForwarder.onPause();
            verticalCardUsageForwarder.onPause();
            verticalCardViewportController.onPause();
        } else if (isSquareMode()) {
            uNotificationHistoryLongPressForwarder.onPause();
            squareUEdgeBoundsController.onPause();
            squareUStabilityController.onPause();
            squareUHostFullscreenController.onPause();
        }
        experienceTweaks.onPause();
        notificationForwarder.onPause();
    }
''',
    "manager active style pause",
)
p.write_text(s)

print("3.30.76 style dormancy patch applied with exact 3.30.75 assertions")
