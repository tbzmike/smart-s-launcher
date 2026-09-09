from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def cut_once(text: str, start: str, end: str, label: str) -> str:
    if text.count(start) != 1 or text.count(end) != 1:
        raise SystemExit(
            f"{label}: expected unique markers, got start={text.count(start)} end={text.count(end)}"
        )
    begin = text.index(start)
    finish = text.index(end, begin)
    return text[:begin] + text[finish:]


# 1. Remove the permanent 550 ms unread-notification UI invalidation loop.
notification_path = Path(
    "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardNotificationHistoryForwarder.java"
)
notification = notification_path.read_text()
notification = replace_once(
    notification,
    "import android.os.Handler;\nimport android.os.Looper;\n",
    "",
    "notification Handler imports",
)
notification = replace_once(
    notification,
    "    private static final long ATTENTION_PULSE_MS = 550L;\n",
    "",
    "attention pulse constant",
)
notification = replace_once(
    notification,
    "    private final Handler attentionHandler = new Handler(Looper.getMainLooper());\n",
    "",
    "attention handler field",
)
notification = replace_once(
    notification,
    "    private boolean attentionBright;\n",
    "",
    "attention brightness field",
)
notification = cut_once(
    notification,
    "    private final Runnable attentionPulse = new Runnable() {\n",
    "    VerticalCardNotificationHistoryForwarder(MainActivity activity,\n",
    "attention pulse runnable",
)
notification = replace_once(
    notification,
    "        startAttentionPulse();\n",
    "",
    "attention pulse start call",
)
notification = cut_once(
    notification,
    "    private void startAttentionPulse() {\n",
    "    private void clearAttentionFor(String notificationId) {\n",
    "attention pulse starter",
)
notification = replace_once(
    notification,
    "        if (attentionBorders.isEmpty()) attentionHandler.removeCallbacks(attentionPulse);\n",
    "",
    "attention clear callback removal",
)
notification = replace_once(
    notification,
    "        attentionHandler.removeCallbacks(attentionPulse);\n",
    "",
    "attention reset callback removal",
)
notification = replace_once(
    notification,
    "        attentionBorders.clear();\n        attentionBright = false;\n",
    "        attentionBorders.clear();\n",
    "attention reset brightness",
)
notification = replace_once(
    notification,
    " * Decoration is applied only when cards are created/rebuilt. It must never run from a permanent\n * global-layout listener because that would recursively walk every card during ordinary layouts\n * and scrolling, and it could overwrite independent metadata such as today's usage time.\n",
    " * Decoration is applied only when cards are created/rebuilt. Unread notifications keep a static\n * attention border; no recurring Handler/invalidations are allowed while Home is idle or scrolling.\n * This prevents notification decoration from competing with ScrollView and marquee frame delivery.\n",
    "notification class performance contract",
)
notification_path.write_text(notification)

# 2. Make frozen-app fallback reconciliation truly background-only unless state changed.
app_provider_path = Path("app/src/main/java/fr/neamar/kiss/dataprovider/AppProvider.java")
app_provider = app_provider_path.read_text()
app_provider = replace_once(
    app_provider,
    "    private final ExecutorService stateExecutor = Executors.newSingleThreadExecutor();\n",
    "    private final ExecutorService stateExecutor = Executors.newSingleThreadExecutor(r -> {\n"
    "        Thread thread = new Thread(r, \"smart-s-frozen-state\");\n"
    "        thread.setPriority(Thread.MIN_PRIORITY);\n"
    "        return thread;\n"
    "    });\n",
    "frozen-state low-priority executor",
)
app_provider = replace_once(
    app_provider,
    "        stateExecutor.execute(() -> {\n            final boolean[] enabledStates = new boolean[snapshot.size()];\n",
    "        stateExecutor.execute(() -> {\n"
    "            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);\n"
    "            final boolean[] enabledStates = new boolean[snapshot.size()];\n"
    "            final ArrayList<Integer> changedIndices = new ArrayList<>();\n",
    "frozen-state worker priority and change list",
)
app_provider = replace_once(
    app_provider,
    "                enabledStates[i] = enabled;\n            }\n\n            stateHandler.post(() -> {\n",
    "                enabledStates[i] = enabled;\n"
    "                // AppPojo state is already read from search/background workers elsewhere.\n"
    "                // Pre-filter here so the main thread never performs an all-app comparison pass.\n"
    "                if (pojo.isDisabled() == enabled) changedIndices.add(i);\n"
    "            }\n\n"
    "            if (changedIndices.isEmpty()) {\n"
    "                reconcileRunning.set(false);\n"
    "                if (launcherUiVisible && isFrozenDetectionEnabled()) {\n"
    "                    scheduleNextReconcile(reconcileDelayMs);\n"
    "                }\n"
    "                return;\n"
    "            }\n\n"
    "            stateHandler.post(() -> {\n",
    "frozen-state no-change fast path",
)
app_provider = replace_once(
    app_provider,
    "                    boolean changed = false;\n"
    "                    for (int i = 0; i < snapshot.size(); i++) {\n"
    "                        AppPojo pojo = snapshot.get(i);\n"
    "                        boolean enabled = enabledStates[i];\n"
    "                        if (pojo.isDisabled() == enabled) {\n"
    "                            pojo.setDisabled(!enabled);\n"
    "                            changed = true;\n"
    "                        }\n"
    "                    }\n",
    "                    boolean changed = false;\n"
    "                    for (int index : changedIndices) {\n"
    "                        AppPojo pojo = snapshot.get(index);\n"
    "                        boolean enabled = enabledStates[index];\n"
    "                        // Re-check on the UI thread in case a LauncherApps callback updated\n"
    "                        // this package while the fallback scan was still running.\n"
    "                        if (pojo.isDisabled() == enabled) {\n"
    "                            pojo.setDisabled(!enabled);\n"
    "                            changed = true;\n"
    "                        }\n"
    "                    }\n",
    "frozen-state changed-only UI pass",
)
app_provider = replace_once(
    app_provider,
    "        // Snapshot the immutable provider list on the main thread, then perform PackageManager and\n"
    "        // LauncherApps calls on a dedicated background worker. Those binder calls were previously\n"
    "        // executed for every app directly on the UI thread every 15 seconds and immediately on\n"
    "        // every Home return.\n",
    "        // Snapshot the immutable provider list, then keep PackageManager/LauncherApps scanning and\n"
    "        // unchanged-state filtering on a low-priority worker. The UI thread receives only packages\n"
    "        // whose disabled state may actually need to change; the common no-change pass posts no UI work.\n",
    "frozen-state performance comment",
)
app_provider_path.write_text(app_provider)

# 3. Version bump: every fix must be an unmistakable upgrade.
build_path = Path("app/build.gradle")
build = build_path.read_text()
build = replace_once(
    build,
    "        // Smart S Launcher 3.30.73 - immediate Home history restoration after search launches\n"
    "        versionCode 501\n"
    "        versionName \"3.30.73\"\n",
    "        // Smart S Launcher 3.30.74 - smooth Home scrolling and marquee performance\n"
    "        versionCode 502\n"
    "        versionName \"3.30.74\"\n",
    "3.30.74 version bump",
)
build_path.write_text(build)

print("3.30.74 smooth Home performance patch applied with exact 3.30.73 assertions")
