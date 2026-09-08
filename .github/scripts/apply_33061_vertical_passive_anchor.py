from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one guarded match, got {count}")
    p.write_text(text.replace(old, new, 1))


# Version bump from the exact green 3.30.60 tree.
gradle = "app/build.gradle"
replace_once(
    gradle,
    "        // Smart S Launcher 3.30.60 - coherent Vertical Cards refresh and scroll stability\n"
    "        versionCode 488\n"
    "        versionName \"3.30.60\"",
    "        // Smart S Launcher 3.30.61 - preserve Vertical Cards viewport through delayed geometry\n"
    "        versionCode 489\n"
    "        versionName \"3.30.61\"",
)

# Remember whether the user was at the newest edge when a deferred data refresh first arrived.
idle = "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardRefreshIdlePolicy.java"
replace_once(
    idle,
    "    private boolean pending;\n"
    "    private boolean touching;\n"
    "    private int lastScrollY;",
    "    private boolean pending;\n"
    "    private boolean touching;\n"
    "    private boolean keepBottom;\n"
    "    private int lastScrollY;",
)
replace_once(
    idle,
    "    void request(int scrollY) {\n"
    "        pending = true;\n"
    "        lastScrollY = scrollY;\n"
    "        stableFrames = 0;\n"
    "    }",
    "    void request(int scrollY, boolean atBottom) {\n"
    "        if (!pending) keepBottom = atBottom;\n"
    "        else keepBottom |= atBottom;\n"
    "        pending = true;\n"
    "        lastScrollY = scrollY;\n"
    "        stableFrames = 0;\n"
    "    }",
)
replace_once(
    idle,
    "    void clear() {\n"
    "        pending = false;",
    "    boolean consumeKeepBottom() {\n"
    "        boolean result = keepBottom;\n"
    "        keepBottom = false;\n"
    "        return result;\n"
    "    }\n\n"
    "    void clear() {\n"
    "        pending = false;",
)
replace_once(
    idle,
    "        touching = false;\n"
    "        stableFrames = 0;\n"
    "    }\n}",
    "        touching = false;\n"
    "        keepBottom = false;\n"
    "        stableFrames = 0;\n"
    "    }\n}",
)

smart = "app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java"
replace_once(
    smart,
    "        deferredRefreshIdlePolicy.request(scroller == null ? 0 : scroller.getScrollY());",
    "        deferredRefreshIdlePolicy.request(\n"
    "                scroller == null ? 0 : scroller.getScrollY(), isAtBottom());",
)
replace_once(
    smart,
    "    void rebuildImmediately() {\n"
    "        if (isEnabled()) rebuild();\n"
    "    }",
    "    boolean consumeDeferredKeepBottom() {\n"
    "        return deferredRefreshIdlePolicy.consumeKeepBottom();\n"
    "    }\n\n"
    "    void rebuildImmediately() {\n"
    "        if (isEnabled()) rebuild();\n"
    "    }",
)
replace_once(
    smart,
    "    private void scheduleDeferredRefreshIdleProbe() {",
    "    private boolean isAtBottom() {\n"
    "        if (scroller == null || column == null || column.getChildCount() == 0) return true;\n"
    "        View content = scroller.getChildAt(0);\n"
    "        if (content == null) return true;\n"
    "        int viewportHeight = Math.max(0, scroller.getHeight()\n"
    "                - scroller.getPaddingTop() - scroller.getPaddingBottom());\n"
    "        int maxScrollY = Math.max(0, content.getHeight() - viewportHeight);\n"
    "        return maxScrollY - scroller.getScrollY() <= dp(6);\n"
    "    }\n\n"
    "    private void scheduleDeferredRefreshIdleProbe() {",
)

# Allow a deferred refresh to carry the already-proven bottom intent into the rebuild snapshot.
policy = "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardViewportPolicy.java"
replace_once(
    policy,
    "    void requestImmediateBottom() {\n"
    "        immediateBottomPending = true;\n"
    "    }",
    "    void requestImmediateBottom() {\n"
    "        immediateBottomPending = true;\n"
    "    }\n\n"
    "    void requestBottomOnNextRebuild() {\n"
    "        forceBottomOnNextRebuild = true;\n"
    "    }",
)

viewport = "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardViewportController.java"
replace_once(
    viewport,
    "    private boolean latestControlsUpdateScheduled;\n"
    "    private boolean resumed;\n"
    "    private int generation;",
    "    private boolean latestControlsUpdateScheduled;\n"
    "    private boolean resumed;\n"
    "    private int generation;\n"
    "    private int scheduledRestoreGeneration = -1;",
)
replace_once(
    viewport,
    "    /** Restore after the card rebuild and all synchronous decorators have been queued. */",
    "    /** Carry a captured newest-edge intent into exactly the next deferred rebuild. */\n"
    "    void forceBottomForNextRebuild() {\n"
    "        policy.requestBottomOnNextRebuild();\n"
    "    }\n\n"
    "    /** Restore after the card rebuild and all synchronous decorators have been queued. */",
)
replace_once(
    viewport,
    "        resolveViews();\n"
    "        if (!canControlViewport()) return null;\n"
    "        if (returnRestoreRequested && savedReturnSnapshot != null",
    "        resolveViews();\n"
    "        // If an authoritative rebuild/return restore is already queued, it will run after\n"
    "        // this mutation and therefore already protects the viewport. Starting a second\n"
    "        // restore here would invalidate or race that transaction.\n"
    "        if (!canControlViewport() || hasCurrentScheduledRestore()) return null;\n"
    "        if (returnRestoreRequested && savedReturnSnapshot != null",
)
replace_once(
    viewport,
    "        latestControlsUpdateScheduled = false;\n"
    "        policy.resetForConfiguration();",
    "        latestControlsUpdateScheduled = false;\n"
    "        scheduledRestoreGeneration = -1;\n"
    "        policy.resetForConfiguration();",
)
replace_once(
    viewport,
    "        latestControlsUpdateScheduled = false;\n"
    "        resumed = false;",
    "        latestControlsUpdateScheduled = false;\n"
    "        scheduledRestoreGeneration = -1;\n"
    "        resumed = false;",
)
replace_once(
    viewport,
    "    private void scheduleRestore(ViewportSnapshot snapshot, int token) {\n"
    "        final ScrollView target = scroller;\n"
    "        if (target == null) return;\n\n"
    "        // Queue behind the rebuild/decorators, then use the next frame's measured card geometry.\n"
    "        target.post(() -> {\n"
    "            if (!isCurrent(token, target)) return;\n"
    "            target.postOnAnimation(() -> {\n"
    "                if (!isCurrent(token, target)) return;\n"
    "                restoreSnapshot(target, snapshot);\n"
    "            });\n"
    "        });\n"
    "    }",
    "    private void scheduleRestore(ViewportSnapshot snapshot, int token) {\n"
    "        final ScrollView target = scroller;\n"
    "        if (target == null) return;\n"
    "        scheduledRestoreGeneration = token;\n\n"
    "        // Queue behind the rebuild/decorators, then use the next frame's measured card geometry.\n"
    "        target.post(() -> {\n"
    "            if (!isCurrent(token, target)) {\n"
    "                clearScheduledRestore(token);\n"
    "                return;\n"
    "            }\n"
    "            target.postOnAnimation(() -> {\n"
    "                if (!isCurrent(token, target)) {\n"
    "                    clearScheduledRestore(token);\n"
    "                    return;\n"
    "                }\n"
    "                try {\n"
    "                    restoreSnapshot(target, snapshot);\n"
    "                } finally {\n"
    "                    clearScheduledRestore(token);\n"
    "                }\n"
    "            });\n"
    "        });\n"
    "    }\n\n"
    "    private boolean hasCurrentScheduledRestore() {\n"
    "        return scheduledRestoreGeneration == generation;\n"
    "    }\n\n"
    "    private void clearScheduledRestore(int token) {\n"
    "        if (scheduledRestoreGeneration == token) scheduledRestoreGeneration = -1;\n"
    "    }",
)

manager = "app/src/main/java/fr/neamar/kiss/forwarder/ForwarderManager.java"
replace_once(
    manager,
    "        this.verticalMapsCardForwarder = new VerticalMapsCardForwarder(mainActivity, smartCardListForwarder);",
    "        this.verticalMapsCardForwarder = new VerticalMapsCardForwarder(\n"
    "                mainActivity, smartCardListForwarder, verticalCardViewportController);",
)
replace_once(
    manager,
    "    private void rebuildDeferredVerticalCards() {\n"
    "        if (!isVerticalCardsMode() || !smartCardListForwarder.hasPendingDataSetRefresh()) return;\n"
    "        verticalCardViewportController.beforeDataSetChanged();",
    "    private void rebuildDeferredVerticalCards() {\n"
    "        if (!isVerticalCardsMode() || !smartCardListForwarder.hasPendingDataSetRefresh()) return;\n"
    "        if (smartCardListForwarder.consumeDeferredKeepBottom()) {\n"
    "            verticalCardViewportController.forceBottomForNextRebuild();\n"
    "        }\n"
    "        verticalCardViewportController.beforeDataSetChanged();",
)

# Delayed launch-stat metadata is outside the manager's synchronous rebuild transaction and must
# use the same viewport protection already used by the full UsageStats refresh.
usage = "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java"
replace_once(
    usage,
    "                launchStats = freshStats;\n"
    "                postApplySnapshot(false, true);\n"
    "            });\n"
    "        });\n"
    "    }\n\n"
    "    private Map<String, LaunchStatsProvider.LaunchStats> loadLaunchStats",
    "                launchStats = freshStats;\n"
    "                postApplySnapshot(true, false);\n"
    "            });\n"
    "        });\n"
    "    }\n\n"
    "    private Map<String, LaunchStatsProvider.LaunchStats> loadLaunchStats",
)

# Maps is another delayed geometry source. Protect every UI mutation; capture returns null while
# an authoritative manager restore is still pending, so the outer restore remains the sole owner.
maps = "app/src/main/java/fr/neamar/kiss/forwarder/VerticalMapsCardForwarder.java"
replace_once(
    maps,
    "    private final SmartCardListForwarder smartCards;\n"
    "    private final AtomicBoolean loadInFlight = new AtomicBoolean(false);\n\n"
    "    VerticalMapsCardForwarder(MainActivity mainActivity, SmartCardListForwarder smartCards) {\n"
    "        super(mainActivity);\n"
    "        this.smartCards = smartCards;\n"
    "    }",
    "    private final SmartCardListForwarder smartCards;\n"
    "    private final VerticalCardViewportController viewportController;\n"
    "    private final AtomicBoolean loadInFlight = new AtomicBoolean(false);\n\n"
    "    VerticalMapsCardForwarder(MainActivity mainActivity, SmartCardListForwarder smartCards,\n"
    "                              VerticalCardViewportController viewportController) {\n"
    "        super(mainActivity);\n"
    "        this.smartCards = smartCards;\n"
    "        this.viewportController = viewportController;\n"
    "    }",
)
replace_once(
    maps,
    "            showLocatingState(position);",
    "            mutatePreservingViewport(() -> showLocatingState(position));",
)
replace_once(
    maps,
    "                if (data == null) {\n"
    "                    if (!MapLiveTileProvider.hasLocationPermission(mainActivity)) {\n"
    "                        updateStatus(adapterPosition, \"Allow location for live Maps preview\");\n"
    "                    } else {\n"
    "                        updateStatus(adapterPosition, \"Locating current position…\");\n"
    "                    }\n"
    "                    return;\n"
    "                }\n"
    "                applyToCard(adapterPosition, data);",
    "                if (data == null) {\n"
    "                    mutatePreservingViewport(() -> {\n"
    "                        if (!MapLiveTileProvider.hasLocationPermission(mainActivity)) {\n"
    "                            updateStatus(adapterPosition, \"Allow location for live Maps preview\");\n"
    "                        } else {\n"
    "                            updateStatus(adapterPosition, \"Locating current position…\");\n"
    "                        }\n"
    "                    });\n"
    "                    return;\n"
    "                }\n"
    "                mutatePreservingViewport(() -> applyToCard(adapterPosition, data));",
)
replace_once(
    maps,
    "    private void showLocatingState(int adapterPosition) {",
    "    private void mutatePreservingViewport(Runnable mutation) {\n"
    "        VerticalCardViewportController.ViewportSnapshot viewport =\n"
    "                viewportController.captureForContentMutation();\n"
    "        mutation.run();\n"
    "        viewportController.restoreAfterContentMutation(viewport);\n"
    "    }\n\n"
    "    private void showLocatingState(int adapterPosition) {",
)

# Focused state tests for the new bottom-intent contract.
test_idle = "app/src/test/java/fr/neamar/kiss/forwarder/VerticalCardRefreshIdlePolicyTest.java"
p = Path(test_idle)
s = p.read_text().replace("policy.request(100);", "policy.request(100, false);")
s = s.replace("policy.request(42);", "policy.request(42, false);")
if s == p.read_text():
    raise SystemExit(f"{test_idle}: request signature replacements did not apply")
insert = """
    @Test
    void bottomIntentSurvivesScrollDriftUntilDeferredRefreshConsumesIt() {
        VerticalCardRefreshIdlePolicy policy = new VerticalCardRefreshIdlePolicy();
        policy.request(200, true);

        assertThat(policy.onAnimationFrame(180), is(false));
        assertThat(policy.consumeKeepBottom(), is(true));
        assertThat(policy.consumeKeepBottom(), is(false));
    }

"""
needle = "    @Test\n    void clearCancelsADeferredRefresh() {"
if s.count(needle) != 1:
    raise SystemExit(f"{test_idle}: insertion point mismatch")
s = s.replace(needle, insert + needle, 1)
p.write_text(s)

test_viewport = "app/src/test/java/fr/neamar/kiss/forwarder/VerticalCardViewportPolicyTest.java"
replace_once(
    test_viewport,
    "    @Test\n    void homeIsImmediateAndDoesNotArmAnUnrelatedFutureRefresh() {",
    "    @Test\n"
    "    void deferredBottomIntentPinsExactlyTheNextRebuild() {\n"
    "        VerticalCardViewportPolicy policy = settledPolicy();\n\n"
    "        policy.requestBottomOnNextRebuild();\n"
    "        assertThat(policy.shouldBottomRebuild(), is(true));\n"
    "        applyBottomRebuild(policy);\n"
    "        assertThat(policy.shouldBottomRebuild(), is(false));\n"
    "        assertThat(policy.shouldPinGeometry(), is(false));\n"
    "    }\n\n"
    "    @Test\n    void homeIsImmediateAndDoesNotArmAnUnrelatedFutureRefresh() {",
)
