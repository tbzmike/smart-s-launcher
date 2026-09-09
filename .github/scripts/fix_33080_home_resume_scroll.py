from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one old block, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def require(path: str, needle: str) -> None:
    text = Path(path).read_text(encoding="utf-8")
    if needle not in text:
        raise SystemExit(f"{path}: missing required baseline text: {needle}")


BUILD = "app/build.gradle"
MAIN = "app/src/main/java/fr/neamar/kiss/MainActivity.java"
NOTIFY = "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardNotificationHistoryForwarder.java"
USAGE = "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java"
PM = "app/src/main/java/fr/neamar/kiss/utils/PackageManagerUtils.java"
POLICY = "app/src/main/java/fr/neamar/kiss/LauncherResumeRefreshPolicy.java"
POLICY_TEST = "app/src/test/java/fr/neamar/kiss/LauncherResumeRefreshPolicyTest.java"

# Exact released 3.30.79 baseline guards.
require(BUILD, 'versionCode 507')
require(BUILD, 'versionName "3.30.79"')
require(MAIN, 'private boolean launcherUiResumed;')
require(MAIN, 'private boolean pendingBackgroundRefresh;')
require(MAIN, 'searchLaunchReturnState.consumeDefaultHistoryReset()')
require(NOTIFY, 'private static final int ATTENTION_PULSE_MS = 550;')
require(NOTIFY, 'private final List<AttentionBorder> attentionBorders = new ArrayList<>();')
require(USAGE, 'void onResume() {\n        paused = false;\n        resolveColumn();\n        refreshSnapshotAsync();\n    }')
require(PM, 'Log.e(TAG, "Unable to find activity icon for component " + componentName.toShortString(), e);')

# 3.30.80 / 508.
replace_once(
    BUILD,
    '''        // Smart S Launcher 3.30.79 - Home lifecycle and historical notification recovery\n        versionCode 507\n        versionName "3.30.79"''',
    '''        // Smart S Launcher 3.30.80 - fast Home resume and Vertical Cards scroll stability\n        versionCode 508\n        versionName "3.30.80"''')

# MainActivity: keep a stable Home frame first; coalesce authoritative refresh work after resume.
replace_once(
    MAIN,
    '''    private boolean pendingBackgroundFavoriteRefresh;\n    @Nullable private String pendingNotificationTargetId;''',
    '''    private boolean pendingBackgroundFavoriteRefresh;\n    @Nullable private String pendingNotificationTargetId;\n    private static final long HOME_RESUME_REFRESH_DELAY_MS = 300L;\n    private boolean pendingHomeReturnFromBackground;\n    private boolean pendingSmartLaunchHistoryRefresh;\n    private boolean deferredResumeRefreshScheduled;\n    private final Runnable deferredResumeRefreshRunnable = this::runDeferredResumeRefresh;''')

replace_once(
    MAIN,
    '''                    if (notificationEvent) {\n                        forwarderManager.onNotificationTimelineChanged(\n                                notificationId, notificationPosted);\n                        updateSearchRecords();\n                        return;\n                    }''',
    '''                    if (notificationEvent) {\n                        forwarderManager.onNotificationTimelineChanged(\n                                notificationId, notificationPosted);\n                        // Notification bursts can arrive while the user is actively scrolling.\n                        // Coalesce their authoritative history refresh instead of starting a new\n                        // HistorySearcher immediately on the UI event that receives the broadcast.\n                        scheduleDeferredResumeRefresh();\n                        return;\n                    }''')

replace_once(
    MAIN,
    '''        boolean resetDefaultHistoryAfterSearchLaunch =\n                searchLaunchReturnState.consumeDefaultHistoryReset();\n        boolean refreshDeferredBackground = pendingBackgroundRefresh;\n        boolean refreshDeferredFavorites = pendingBackgroundFavoriteRefresh;\n        String deferredNotificationTargetId = pendingNotificationTargetId;\n        pendingBackgroundRefresh = false;\n        pendingBackgroundFavoriteRefresh = false;\n        pendingNotificationTargetId = null;''',
    '''        boolean resetDefaultHistoryAfterSearchLaunch =\n                searchLaunchReturnState.consumeDefaultHistoryReset();\n        boolean returningHomeFromBackground = pendingHomeReturnFromBackground;\n        boolean refreshAfterSmartLaunch = pendingSmartLaunchHistoryRefresh;\n        boolean refreshDeferredBackground = pendingBackgroundRefresh;\n        boolean refreshDeferredFavorites = pendingBackgroundFavoriteRefresh;\n        String deferredNotificationTargetId = pendingNotificationTargetId;\n        pendingHomeReturnFromBackground = false;\n        pendingSmartLaunchHistoryRefresh = false;\n        pendingBackgroundRefresh = false;\n        pendingBackgroundFavoriteRefresh = false;\n        pendingNotificationTargetId = null;''')

old_resume_search = '''        // A successful launch from a query must return to the real default History tree, not a\n        // visually frozen copy of the old query results. Arm the Vertical Cards renderer before\n        // publishing the empty query so the QUERY -> HISTORY result set is rebuilt and bottom-pinned.\n        if (resetDefaultHistoryAfterSearchLaunch) {\n            forwarderManager.prepareDefaultHistoryAfterSearchLaunch();\n            if (!TextUtils.isEmpty(searchEditText.getText())) {\n                clearSearchText();\n            }\n            // A cancelled provider query can still be unwinding on the serialized search worker.\n            // Do not leave its QUERY adapter visible while the authoritative History search waits\n            // for that worker. Restore a safe cached/recent History snapshot immediately, refresh\n            // that snapshot on the independent history-seed worker, then let the full History\n            // search run on the normal serialized lane.\n            SearchHandler.getInstance().restoreHomeHistory(this);\n            displayClearOnInput();\n            hideKeyboard();\n        } else if (refreshDeferredBackground\n                && SearchHandler.getInstance().getLastSearchType() != null) {\n            updateSearchRecords(true, searchEditText.getText().toString());\n        } else {\n            updateSearchRecords(false, searchEditText.getText().toString());\n        }'''

new_resume_search = '''        // A successful launch from a query must return to the real default History tree, not a\n        // visually frozen copy of the old query results. Ordinary HOME returns, however, must not\n        // synchronously restart a full HistorySearcher before Android can draw the first frame.\n        boolean scheduleRefreshAfterResume = false;\n        LauncherResumeRefreshPolicy.Action resumeRefreshAction =\n                LauncherResumeRefreshPolicy.decide(\n                        returningHomeFromBackground,\n                        resetDefaultHistoryAfterSearchLaunch,\n                        refreshDeferredBackground,\n                        refreshAfterSmartLaunch,\n                        SearchHandler.getInstance().getLastSearchType() != null);\n        if (resumeRefreshAction == LauncherResumeRefreshPolicy.Action.RESTORE_QUERY_HISTORY) {\n            forwarderManager.prepareDefaultHistoryAfterSearchLaunch();\n            if (!TextUtils.isEmpty(searchEditText.getText())) {\n                clearSearchText();\n            }\n            // A cancelled provider query can still be unwinding on the serialized search worker.\n            // Restore a safe cached/recent History snapshot immediately; SearchHandler keeps the\n            // authoritative full History search on its serialized background lane.\n            SearchHandler.getInstance().restoreHomeHistory(this);\n            displayClearOnInput();\n            hideKeyboard();\n        } else if (resumeRefreshAction == LauncherResumeRefreshPolicy.Action.DEFER_REFRESH) {\n            scheduleRefreshAfterResume = true;\n        } else if (resumeRefreshAction == LauncherResumeRefreshPolicy.Action.NORMAL_REFRESH) {\n            updateSearchRecords(false, searchEditText.getText().toString());\n        } else {\n            // KEEP_VISIBLE: the current Home adapter/card tree is already valid. Do not create a\n            // duplicate History search simply because Android redelivered CATEGORY_HOME.\n            displayClearOnInput();\n        }'''
replace_once(MAIN, old_resume_search, new_resume_search)

replace_once(
    MAIN,
    '''        super.onResume();\n        homeLifecycleState.onResumeCompleted();''',
    '''        super.onResume();\n        homeLifecycleState.onResumeCompleted();\n        if (scheduleRefreshAfterResume) scheduleDeferredResumeRefresh();''')

replace_once(
    MAIN,
    '''    protected void onPause() {\n        launcherUiResumed = false;\n        forwarderManager.onPause();\n        super.onPause();\n    }''',
    '''    protected void onPause() {\n        launcherUiResumed = false;\n        cancelDeferredResumeRefresh(true);\n        forwarderManager.onPause();\n        super.onPause();\n    }''')

replace_once(
    MAIN,
    '''    protected void onDestroy() {\n        super.onDestroy();\n        onBackPressedCallback.remove();''',
    '''    protected void onDestroy() {\n        cancelDeferredResumeRefresh(false);\n        super.onDestroy();\n        onBackPressedCallback.remove();''')

replace_once(
    MAIN,
    '''        boolean homeIntent = Intent.ACTION_MAIN.equals(intent.getAction())\n                && intent.hasCategory(Intent.CATEGORY_HOME);\n        if (homeIntent) {''',
    '''        boolean homeIntent = Intent.ACTION_MAIN.equals(intent.getAction())\n                && intent.hasCategory(Intent.CATEGORY_HOME);\n        pendingHomeReturnFromBackground = homeIntent && !launcherWasForeground;\n        if (homeIntent) {''')

replace_once(
    MAIN,
    '''    public void externalResultLaunchOccurred() {\n        searchLaunchReturnState.onExternalLaunchSucceeded();\n        homeLifecycleState.onExternalResultLaunched();\n    }''',
    '''    public void externalResultLaunchOccurred() {\n        searchLaunchReturnState.onExternalLaunchSucceeded();\n        homeLifecycleState.onExternalResultLaunched();\n        pendingSmartLaunchHistoryRefresh = true;\n    }''')

replace_once(
    MAIN,
    '''    public void externalResultLaunchCancelled() {\n        searchLaunchReturnState.onExternalLaunchCancelled();\n        launcherUiResumed = true;\n        forwarderManager.onExternalResultLaunchCancelled();\n    }''',
    '''    public void externalResultLaunchCancelled() {\n        searchLaunchReturnState.onExternalLaunchCancelled();\n        pendingSmartLaunchHistoryRefresh = false;\n        launcherUiResumed = true;\n        forwarderManager.onExternalResultLaunchCancelled();\n    }''')

replace_once(
    MAIN,
    '''    private void cancelSearch() {\n        SearchHandler.getInstance().cancelSearch();\n    }''',
    '''    private void cancelSearch() {\n        SearchHandler.getInstance().cancelSearch();\n    }\n\n    /**\n     * Coalesce refreshes that become necessary while HOME is backgrounded. The current adapter and\n     * Vertical Cards tree stay on screen for the first Home frame; the authoritative provider/history\n     * refresh starts only after Android has resumed and focused the launcher.\n     */\n    private void scheduleDeferredResumeRefresh() {\n        if (SearchHandler.getInstance().getLastSearchType() == null) return;\n        View root = findViewById(android.R.id.content);\n        if (root == null) return;\n        root.removeCallbacks(deferredResumeRefreshRunnable);\n        deferredResumeRefreshScheduled = true;\n        root.postDelayed(deferredResumeRefreshRunnable, HOME_RESUME_REFRESH_DELAY_MS);\n    }\n\n    private void runDeferredResumeRefresh() {\n        deferredResumeRefreshScheduled = false;\n        if (!launcherUiResumed || isFinishing() || isDestroyed()) {\n            pendingBackgroundRefresh = true;\n            return;\n        }\n        if (SearchHandler.getInstance().getLastSearchType() == null) return;\n        updateSearchRecords(true, searchEditText.getText().toString());\n    }\n\n    private void cancelDeferredResumeRefresh(boolean preserveRefresh) {\n        if (!deferredResumeRefreshScheduled) return;\n        View root = findViewById(android.R.id.content);\n        if (root != null) root.removeCallbacks(deferredResumeRefreshRunnable);\n        deferredResumeRefreshScheduled = false;\n        if (preserveRefresh) pendingBackgroundRefresh = true;\n    }''')

# Vertical Cards notification decoration: resume existing bindings, refresh only stats, and stop
# unread border invalidations while scrolling.
replace_once(
    NOTIFY,
    'import android.view.ViewGroup;\n',
    'import android.view.ViewGroup;\nimport android.view.ViewTreeObserver;\n')

replace_once(
    NOTIFY,
    '''    private static final int ATTENTION_PULSE_MS = 550;''',
    '''    private static final int ATTENTION_PULSE_MS = 550;\n    private static final long ATTENTION_SCROLL_IDLE_MS = 160L;''')

replace_once(
    NOTIFY,
    '''    private boolean launchStatsRefreshRequested;\n    private boolean paused;\n    private volatile boolean destroyed;''',
    '''    private boolean launchStatsRefreshRequested;\n    private boolean paused;\n    private boolean decorated;\n    private boolean attentionSuspendedForScroll;\n    private ScrollView observedScroller;\n    private final Runnable resumeAttentionAfterScroll = this::finishAttentionScrollPause;\n    private final ViewTreeObserver.OnScrollChangedListener attentionScrollListener =\n            this::onAttentionScrollChanged;\n    private volatile boolean destroyed;''')

replace_once(
    NOTIFY,
    '''    void onCreate() { paused = false; refresh(); }\n    void onResume() { paused = false; refresh(); }\n    void onDataSetChanged() { refresh(); }\n    void onConfigurationChanged() { refresh(); }\n\n    void onPause() {\n        paused = true;\n        resetBottomSwipe();\n        resetAttentionBorders();\n    }''',
    '''    void onCreate() { paused = false; refresh(); }\n\n    void onResume() {\n        paused = false;\n        resolveViews();\n        if (!decorated && column != null) column.post(this::apply);\n        else resumeAttentionBorders();\n        // Launch-count metadata may have changed while another app was foreground. Updating only\n        // that strip avoids recursively rewiring every card and notification preview on each HOME.\n        refreshLaunchStatsAsync();\n    }\n\n    void onDataSetChanged() { refresh(); }\n    void onConfigurationChanged() { refresh(); }\n\n    void onPause() {\n        paused = true;\n        resetBottomSwipe();\n        cancelAttentionScrollResume();\n        suspendAttentionBorders();\n    }''')

replace_once(
    NOTIFY,
    '''        launchStatsExecutor.shutdownNow();\n        resetBottomSwipe();\n        resetAttentionBorders();\n        column = null;\n        scroller = null;''',
    '''        launchStatsExecutor.shutdownNow();\n        resetBottomSwipe();\n        cancelAttentionScrollResume();\n        detachAttentionScrollObserver();\n        resetAttentionBorders();\n        decorated = false;\n        column = null;\n        scroller = null;''')

replace_once(
    NOTIFY,
    '''        resolveViews();\n        if (!paused && column != null) column.post(this::apply);\n        refreshLaunchStatsAsync();''',
    '''        resolveViews();\n        decorated = false;\n        if (!paused && column != null) column.post(this::apply);\n        refreshLaunchStatsAsync();''')

replace_once(
    NOTIFY,
    '''                launchStats = result;\n                resolveViews();\n                if (!paused && column != null) column.post(this::apply);''',
    '''                launchStats = result;\n                resolveViews();\n                if (!paused && column != null) column.post(this::applyLaunchStatsOnly);''')

replace_once(
    NOTIFY,
    '''    private void resolveViews() {\n        column = smartCardListForwarder.getColumn();\n        scroller = smartCardListForwarder.getScroller();\n        if (column == null || scroller == null) {\n            resetBottomSwipe();\n            resetAttentionBorders();\n        }\n    }''',
    '''    private void resolveViews() {\n        column = smartCardListForwarder.getColumn();\n        ScrollView resolvedScroller = smartCardListForwarder.getScroller();\n        if (resolvedScroller != scroller) {\n            detachAttentionScrollObserver();\n            scroller = resolvedScroller;\n            attachAttentionScrollObserver();\n        }\n        if (column == null || scroller == null) {\n            resetBottomSwipe();\n            resetAttentionBorders();\n        }\n    }\n\n    private void attachAttentionScrollObserver() {\n        if (scroller == null) return;\n        ViewTreeObserver observer = scroller.getViewTreeObserver();\n        if (observer.isAlive()) {\n            observer.addOnScrollChangedListener(attentionScrollListener);\n            observedScroller = scroller;\n        }\n    }\n\n    private void detachAttentionScrollObserver() {\n        ScrollView observed = observedScroller;\n        observedScroller = null;\n        if (observed == null) return;\n        ViewTreeObserver observer = observed.getViewTreeObserver();\n        if (observer.isAlive()) observer.removeOnScrollChangedListener(attentionScrollListener);\n        observed.removeCallbacks(resumeAttentionAfterScroll);\n    }\n\n    private void onAttentionScrollChanged() {\n        if (paused || destroyed || scroller == null || attentionBorders.isEmpty()) return;\n        if (!attentionSuspendedForScroll) {\n            attentionSuspendedForScroll = true;\n            suspendAttentionBorders();\n        }\n        scroller.removeCallbacks(resumeAttentionAfterScroll);\n        scroller.postDelayed(resumeAttentionAfterScroll, ATTENTION_SCROLL_IDLE_MS);\n    }\n\n    private void finishAttentionScrollPause() {\n        attentionSuspendedForScroll = false;\n        resumeAttentionBorders();\n    }\n\n    private void cancelAttentionScrollResume() {\n        if (scroller != null) scroller.removeCallbacks(resumeAttentionAfterScroll);\n        attentionSuspendedForScroll = false;\n    }''')

replace_once(
    NOTIFY,
    '''    private void apply() {\n        if (paused || destroyed || !isEnabled() || column == null || mainActivity.adapter == null) return;\n        resetAttentionBorders();''',
    '''    private void apply() {\n        if (paused || destroyed || !isEnabled() || column == null || mainActivity.adapter == null) return;\n        resetAttentionBorders();\n        decorated = true;''')

replace_once(
    NOTIFY,
    '''            if (!attentionBorders.contains(binding)\n                    || !card.isAttachedToWindow()\n                    || !NotificationTimelineState.isUnread(mainActivity, notification.id)) return;\n            updateAttentionBounds(card, border);\n            card.getOverlay().add(border);\n            border.start();''',
    '''            if (!attentionBorders.contains(binding)\n                    || paused\n                    || attentionSuspendedForScroll\n                    || !card.isAttachedToWindow()\n                    || !NotificationTimelineState.isUnread(mainActivity, notification.id)) return;\n            updateAttentionBounds(card, border);\n            card.getOverlay().add(border);\n            border.start();''')

replace_once(
    NOTIFY,
    '''    private void resetAttentionBorders() {\n        for (AttentionBorder binding : attentionBorders) removeAttentionBinding(binding);\n        attentionBorders.clear();\n    }\n\n    private void removeAttentionBinding(AttentionBorder binding) {''',
    '''    private void resetAttentionBorders() {\n        for (AttentionBorder binding : attentionBorders) removeAttentionBinding(binding);\n        attentionBorders.clear();\n    }\n\n    private void suspendAttentionBorders() {\n        for (AttentionBorder binding : attentionBorders) binding.border.stop();\n    }\n\n    private void resumeAttentionBorders() {\n        if (paused || destroyed || attentionSuspendedForScroll) return;\n        for (int i = attentionBorders.size() - 1; i >= 0; i--) {\n            AttentionBorder binding = attentionBorders.get(i);\n            if (!binding.card.isAttachedToWindow()\n                    || !NotificationTimelineState.isUnread(mainActivity, binding.notificationId)) {\n                removeAttentionBinding(binding);\n                attentionBorders.remove(i);\n                continue;\n            }\n            updateAttentionBounds(binding.card, binding.border);\n            binding.border.start();\n        }\n    }\n\n    private void removeAttentionBinding(AttentionBorder binding) {''')

replace_once(
    NOTIFY,
    '''    private void applyLaunchStats(View wrapper, Result<?> result) {''',
    '''    private void applyLaunchStatsOnly() {\n        if (paused || destroyed || !isEnabled() || column == null || mainActivity.adapter == null) return;\n        Map<String, Result<?>> resultsByPojoId = new HashMap<>();\n        for (int position = 0; position < mainActivity.adapter.getCount(); position++) {\n            Result<?> result = mainActivity.adapter.getItem(position);\n            if (result != null) resultsByPojoId.put(result.getPojoId(), result);\n        }\n        for (int position = 0; position < column.getChildCount(); position++) {\n            View wrapper = column.getChildAt(position);\n            Object wrapperId = wrapper.getTag();\n            if (!(wrapperId instanceof String)) continue;\n            Result<?> result = resultsByPojoId.get((String) wrapperId);\n            if (result != null) applyLaunchStats(wrapper, result);\n        }\n    }\n\n    private void applyLaunchStats(View wrapper, Result<?> result) {''')

# Vertical Cards usage metadata: full UsageStats reads are useful, but not on every short app -> Home
# trip. Keep launch-count refreshes current and throttle the expensive daily usage snapshot.
replace_once(USAGE, 'import android.graphics.Color;\n', 'import android.graphics.Color;\nimport android.os.SystemClock;\n')
replace_once(
    USAGE,
    '''    private static final String USAGE_VIEW_TAG = "smart-s-used-today";''',
    '''    private static final String USAGE_VIEW_TAG = "smart-s-used-today";\n    private static final long RESUME_USAGE_REFRESH_MIN_INTERVAL_MS = 30_000L;''')
replace_once(
    USAGE,
    '''    private boolean pendingApplyFromDataSet;\n    private boolean pendingApplyNeedsViewportProtection;''',
    '''    private boolean pendingApplyFromDataSet;\n    private boolean pendingApplyNeedsViewportProtection;\n    private long lastFullRefreshElapsedMs;''')
replace_once(
    USAGE,
    '''    void onResume() {\n        paused = false;\n        resolveColumn();\n        refreshSnapshotAsync();\n    }''',
    '''    void onResume() {\n        paused = false;\n        resolveColumn();\n        long now = SystemClock.elapsedRealtime();\n        if (snapshot == null || lastFullRefreshElapsedMs <= 0L\n                || now - lastFullRefreshElapsedMs >= RESUME_USAGE_REFRESH_MIN_INTERVAL_MS) {\n            refreshSnapshotAsync();\n        } else {\n            // A short app -> Home round trip normally changes launch counts, not the whole daily\n            // UsageStats snapshot. Refresh the lightweight launch DB only.\n            refreshLaunchStatsAsync();\n        }\n    }''')
replace_once(
    USAGE,
    '''                launchStats = freshStats;\n                snapshot = fresh;\n                shortcutSnapshot = freshShortcuts;\n                loadedShortcutTargets = requestedTargets;''',
    '''                launchStats = freshStats;\n                snapshot = fresh;\n                shortcutSnapshot = freshShortcuts;\n                lastFullRefreshElapsedMs = SystemClock.elapsedRealtime();\n                loadedShortcutTargets = requestedTargets;''')
replace_once(
    USAGE,
    '''        pendingApplyFromDataSet = false;\n        pendingApplyNeedsViewportProtection = false;\n    }''',
    '''        pendingApplyFromDataSet = false;\n        pendingApplyNeedsViewportProtection = false;\n        lastFullRefreshElapsedMs = 0L;\n    }''')

# Stale launcher aliases/components are normal after app updates. Keep the fallback, but do not emit
# a full exception stack for every dead history component/icon resolution.
replace_once(
    PM,
    '''        } catch (PackageManager.NameNotFoundException e) {\n            Log.e(TAG, "Unable to find activity icon for component " + componentName.toShortString(), e);\n        }''',
    '''        } catch (PackageManager.NameNotFoundException ignored) {\n            Log.d(TAG, "Activity icon component no longer exists; using application icon: "\n                    + componentName.toShortString());\n        }''')

policy_source = '''package fr.neamar.kiss;\n\n/**\n * Pure policy for launcher resume work. Android may redeliver CATEGORY_HOME hundreds of times per\n * day; an unchanged, already-rendered Home must not synchronously restart an expensive History\n * search before the first frame. Real deferred data changes still refresh after resume.\n */\nfinal class LauncherResumeRefreshPolicy {\n    enum Action {\n        RESTORE_QUERY_HISTORY,\n        DEFER_REFRESH,\n        KEEP_VISIBLE,\n        NORMAL_REFRESH\n    }\n\n    private LauncherResumeRefreshPolicy() { }\n\n    static Action decide(boolean returningHomeFromBackground,\n                         boolean resetDefaultHistoryAfterSearchLaunch,\n                         boolean pendingBackgroundRefresh,\n                         boolean smartLaunchHistoryRefresh,\n                         boolean hasLastSearchType) {\n        if (resetDefaultHistoryAfterSearchLaunch) return Action.RESTORE_QUERY_HISTORY;\n        if ((pendingBackgroundRefresh || smartLaunchHistoryRefresh) && hasLastSearchType) {\n            return Action.DEFER_REFRESH;\n        }\n        if (returningHomeFromBackground) return Action.KEEP_VISIBLE;\n        return Action.NORMAL_REFRESH;\n    }\n}\n'''

policy_test_source = '''package fr.neamar.kiss;\n\nimport static org.hamcrest.MatcherAssert.assertThat;\nimport static org.hamcrest.Matchers.is;\n\nimport org.junit.jupiter.api.Test;\n\nclass LauncherResumeRefreshPolicyTest {\n    @Test\n    void unchangedBackgroundHomeReturnKeepsVisibleTree() {\n        assertThat(LauncherResumeRefreshPolicy.decide(true, false, false, false, true),\n                is(LauncherResumeRefreshPolicy.Action.KEEP_VISIBLE));\n    }\n\n    @Test\n    void realBackgroundChangeIsDeferredPastFirstHomeFrame() {\n        assertThat(LauncherResumeRefreshPolicy.decide(true, false, true, false, true),\n                is(LauncherResumeRefreshPolicy.Action.DEFER_REFRESH));\n    }\n\n    @Test\n    void smartLaunchHistoryChangeIsDeferredPastFirstHomeFrame() {\n        assertThat(LauncherResumeRefreshPolicy.decide(true, false, false, true, true),\n                is(LauncherResumeRefreshPolicy.Action.DEFER_REFRESH));\n    }\n\n    @Test\n    void queryLaunchRestorationKeepsPriority() {\n        assertThat(LauncherResumeRefreshPolicy.decide(true, true, true, true, true),\n                is(LauncherResumeRefreshPolicy.Action.RESTORE_QUERY_HISTORY));\n    }\n\n    @Test\n    void normalNonHomeResumeStillRefreshes() {\n        assertThat(LauncherResumeRefreshPolicy.decide(false, false, false, false, true),\n                is(LauncherResumeRefreshPolicy.Action.NORMAL_REFRESH));\n    }\n}\n'''

if Path(POLICY).exists() or Path(POLICY_TEST).exists():
    raise SystemExit("3.30.80 policy files unexpectedly already exist")
Path(POLICY).write_text(policy_source, encoding="utf-8")
Path(POLICY_TEST).write_text(policy_test_source, encoding="utf-8")

print("3.30.80 Home resume + Vertical Cards scroll stability transformations applied")
