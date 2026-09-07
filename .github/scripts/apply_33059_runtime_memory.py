from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path, start_marker, end_marker, replacement):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if text.count(start_marker) != 1:
        raise SystemExit(f"{path}: start marker is not unique")
    if text.count(end_marker) != 1:
        raise SystemExit(f"{path}: end marker is not unique")
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    if end <= start:
        raise SystemExit(f"{path}: invalid replacement range")
    p.write_text(text[:start] + replacement + text[end:], encoding="utf-8")


# 1) Vertical Cards: retain only the lightweight visible card. The full adapter row is rebuilt
# lazily while details are open and released again on collapse.
path = "app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java"
old = """        prepareSourceForDetails(source);
        FrameLayout detailsPanel = new FrameLayout(mainActivity);
        detailsPanel.setVisibility(View.GONE);
        detailsPanel.setPadding(dp(4), dp(7), dp(4), dp(2));
        detailsPanel.addView(source, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean hasDetails = hasMeaningfulVisibleContent(source);
        if (hasDetails) {
            card.addView(detailsPanel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView details = new TextView(mainActivity);
            details.setText("⌄");
            details.setTextColor(Color.WHITE);
            details.setTextSize(18f);
            details.setGravity(Gravity.CENTER);
            details.setContentDescription("Show card details");
            details.setBackground(makePill(accent));
            LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(dp(38), dp(30));
            detailsLp.gravity = Gravity.END;
            detailsLp.topMargin = dp(4);
            card.addView(details, detailsLp);
            details.setOnClickListener(v -> toggleDetails(detailsPanel, details));
        }
"""
new = """        prepareSourceForDetails(source);
        boolean hasDetails = hasMeaningfulVisibleContent(source);
        // The original adapter row is needed only while extracting the lightweight card above.
        // Keeping it hidden under every card doubles the view tree and can retain native
        // notification RemoteViews/drawables. Release that tree now and recreate details lazily.
        releaseDiscardedSource(source);

        if (hasDetails) {
            FrameLayout detailsPanel = new FrameLayout(mainActivity);
            detailsPanel.setVisibility(View.GONE);
            detailsPanel.setPadding(dp(4), dp(7), dp(4), dp(2));
            card.addView(detailsPanel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView details = new TextView(mainActivity);
            details.setText("⌄");
            details.setTextColor(Color.WHITE);
            details.setTextSize(18f);
            details.setGravity(Gravity.CENTER);
            details.setContentDescription("Show card details");
            details.setBackground(makePill(accent));
            LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(dp(38), dp(30));
            detailsLp.gravity = Gravity.END;
            detailsLp.topMargin = dp(4);
            card.addView(details, detailsLp);
            final String expectedPojoId = result.getPojoId();
            details.setOnClickListener(v -> toggleDetails(
                    detailsPanel, details, adapterPosition, expectedPojoId));
        }
"""
replace_once(path, old, new)

old = """    private void toggleDetails(View detailsPanel, TextView control) {
        boolean opening = detailsPanel.getVisibility() != View.VISIBLE;
        detailsPanel.animate().cancel();
        if (opening) {
            detailsPanel.setAlpha(0f);
            detailsPanel.setVisibility(View.VISIBLE);
            control.setText("⌃");
            if (SmartAnimationEngine.isEnabled(mainActivity)) {
                detailsPanel.animate().alpha(1f)
                        .translationY(0f)
                        .setDuration(Math.max(90L, SmartAnimationEngine.duration(mainActivity) / 2))
                        .start();
            } else {
                detailsPanel.setAlpha(1f);
            }
        } else {
            control.setText("⌄");
            if (SmartAnimationEngine.isEnabled(mainActivity)) {
                detailsPanel.animate().alpha(0f)
                        .translationY(dp(6))
                        .setDuration(Math.max(80L, SmartAnimationEngine.duration(mainActivity) / 2))
                        .withEndAction(() -> {
                            detailsPanel.setVisibility(View.GONE);
                            detailsPanel.setAlpha(1f);
                            detailsPanel.setTranslationY(0f);
                        }).start();
            } else {
                detailsPanel.setVisibility(View.GONE);
            }
        }
    }

    private void detachFromParent(View view) {
"""
new = """    private void toggleDetails(FrameLayout detailsPanel, TextView control,
                               int adapterPosition, String expectedPojoId) {
        boolean opening = detailsPanel.getVisibility() != View.VISIBLE;
        detailsPanel.animate().cancel();
        if (opening) {
            if (!populateDetails(detailsPanel, adapterPosition, expectedPojoId)) return;
            detailsPanel.setAlpha(0f);
            detailsPanel.setVisibility(View.VISIBLE);
            control.setText("⌃");
            if (SmartAnimationEngine.isEnabled(mainActivity)) {
                detailsPanel.animate().alpha(1f)
                        .translationY(0f)
                        .setDuration(Math.max(90L, SmartAnimationEngine.duration(mainActivity) / 2))
                        .start();
            } else {
                detailsPanel.setAlpha(1f);
            }
        } else {
            control.setText("⌄");
            if (SmartAnimationEngine.isEnabled(mainActivity)) {
                detailsPanel.animate().alpha(0f)
                        .translationY(dp(6))
                        .setDuration(Math.max(80L, SmartAnimationEngine.duration(mainActivity) / 2))
                        .withEndAction(() -> clearDetailsPanel(detailsPanel)).start();
            } else {
                clearDetailsPanel(detailsPanel);
            }
        }
    }

    private boolean populateDetails(FrameLayout detailsPanel, int adapterPosition,
                                    String expectedPojoId) {
        if (detailsPanel.getChildCount() > 0) return true;
        if (mainActivity.adapter == null
                || adapterPosition < 0
                || adapterPosition >= mainActivity.adapter.getCount()) {
            return false;
        }

        Result<?> current = mainActivity.adapter.getItem(adapterPosition);
        if (current == null || !TextUtils.equals(expectedPojoId, current.getPojoId())) {
            // History can be re-ranked after a launch/notification. Never bind a stale position to
            // a different card merely to populate optional details.
            return false;
        }

        View detailSource = mainActivity.adapter.getView(adapterPosition, null, detailsPanel);
        prepareSourceForDetails(detailSource);
        if (!hasMeaningfulVisibleContent(detailSource)) {
            releaseDiscardedSource(detailSource);
            return false;
        }
        detailsPanel.addView(detailSource, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return true;
    }

    private void clearDetailsPanel(FrameLayout detailsPanel) {
        detailsPanel.removeAllViews();
        detailsPanel.setVisibility(View.GONE);
        detailsPanel.setAlpha(1f);
        detailsPanel.setTranslationY(0f);
    }

    private void releaseDiscardedSource(View source) {
        if (source == null) return;
        // AppResult's detached Mark-read control legitimately keeps its click listener. That
        // listener references the old notification row, so empty that row before discarding the
        // source to prevent it from pinning native notification content in memory.
        View notification = source.findViewById(R.id.item_notification_row);
        if (notification instanceof ViewGroup) {
            ((ViewGroup) notification).removeAllViews();
        }
        source.animate().cancel();
        source.setOnClickListener(null);
        source.setOnLongClickListener(null);
        source.setBackground(null);
        if (source instanceof ViewGroup) {
            ((ViewGroup) source).removeAllViews();
        } else if (source instanceof ImageView) {
            ((ImageView) source).setImageDrawable(null);
        } else if (source instanceof TextView) {
            ((TextView) source).setText(null);
        }
    }

    private void detachFromParent(View view) {
"""
replace_once(path, old, new)


# 2) Usage timeline: never ask UsageStatsManager to materialize up to 365 days of raw events in
# one parcel/list. Process the exact same retention interval in bounded windows, preserving session
# state across windows and caching package metadata only for the duration of this sync.
path = "app/src/main/java/fr/neamar/kiss/appusage/AppUsageSync.java"
replace_once(
    path,
    "    private static final long EVENT_OVERLAP_MS = 24L * 60L * 60L * 1000L;\n"
    "    private static final long AGGREGATE_OVERLAP_MS = 3L * 24L * 60L * 60L * 1000L;\n",
    "    private static final long EVENT_OVERLAP_MS = 24L * 60L * 60L * 1000L;\n"
    "    private static final long RAW_EVENT_QUERY_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L;\n"
    "    private static final long AGGREGATE_OVERLAP_MS = 3L * 24L * 60L * 60L * 1000L;\n",
)

start_marker = "    private static void importUsageEvents(Context context, AppUsageStore store, long now) {\n"
end_marker = "    private static String interactionDetail(UsageEvents.Event event) {\n"
new_method = """    private static void importUsageEvents(Context context, AppUsageStore store, long now) {
        UsageStatsManager manager = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return;

        long firstAllowed = now - AppUsageStore.RETENTION_MS;
        long lastSync = store.getMeta(META_LAST_EVENT_SYNC, 0L);
        long begin = lastSync <= 0L ? firstAllowed
                : Math.max(firstAllowed, lastSync - EVENT_OVERLAP_MS);

        PackageManager pm = context.getPackageManager();
        Map<String, SessionStart> foregroundStarts = new HashMap<>();
        Map<String, PackageMeta> packageMetaCache = new HashMap<>();
        int screenState = 0; // 1 interactive, -1 non-interactive, 0 unknown
        long screenStateStart = 0L;
        long windowStart = begin;

        while (windowStart < now) {
            long windowEnd = Math.min(now, windowStart + RAW_EVENT_QUERY_WINDOW_MS);
            UsageEvents events;
            try {
                events = manager.queryEvents(windowStart, windowEnd);
            } catch (RuntimeException e) {
                // Leave the watermark untouched. A later job retries the same data rather than
                // silently creating a gap in the 365-day local timeline.
                return;
            }

            if (events != null) {
                UsageEvents.Event event = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    long time = event.getTimeStamp();
                    if (time < firstAllowed || time > now) continue;
                    int type = event.getEventType();
                    String pkg = event.getPackageName();

                    // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED share value 1 across supported versions.
                    if (type == 1 && !TextUtils.isEmpty(pkg)) {
                        foregroundStarts.put(pkg, new SessionStart(time, event.getClassName()));
                        continue;
                    }
                    // MOVE_TO_BACKGROUND / ACTIVITY_PAUSED share value 2.
                    if (type == 2 && !TextUtils.isEmpty(pkg)) {
                        SessionStart start = foregroundStarts.remove(pkg);
                        if (start != null && time >= start.timeMs) {
                            PackageMeta meta = packageMetaCache.computeIfAbsent(
                                    pkg, p -> packageMeta(pm, p, null));
                            String detail = TextUtils.isEmpty(start.className)
                                    ? "Foreground app session"
                                    : "Foreground app session · " + start.className;
                            store.putTimeline(new AppUsageStore.TimelineEntry(
                                    "use:" + pkg + ":" + start.timeMs,
                                    start.timeMs, time, AppUsageStore.KIND_APP_USAGE, pkg, meta.label,
                                    time - start.timeMs, meta.system, detail, null, null));
                        }
                        continue;
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            && type == UsageEvents.Event.SCREEN_INTERACTIVE) {
                        if (screenState == -1 && screenStateStart > 0L && time >= screenStateStart) {
                            store.putTimeline(screenEntry(AppUsageStore.KIND_SCREEN_OFF,
                                    screenStateStart, time));
                        }
                        screenState = 1;
                        screenStateStart = time;
                        continue;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            && type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                        if (screenState == 1 && screenStateStart > 0L && time >= screenStateStart) {
                            store.putTimeline(screenEntry(AppUsageStore.KIND_SCREEN_ON,
                                    screenStateStart, time));
                        }
                        screenState = -1;
                        screenStateStart = time;
                        continue;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            && type == UsageEvents.Event.KEYGUARD_SHOWN) {
                        store.putTimeline(pointEntry("locked:" + time, time,
                                AppUsageStore.KIND_LOCKED, "Phone locked"));
                        continue;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            && type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                        store.putTimeline(pointEntry("unlocked:" + time, time,
                                AppUsageStore.KIND_UNLOCKED, "Phone unlocked"));
                        continue;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            && type == UsageEvents.Event.USER_INTERACTION && !TextUtils.isEmpty(pkg)) {
                        long minute = time / 60_000L;
                        PackageMeta meta = packageMetaCache.computeIfAbsent(
                                pkg, p -> packageMeta(pm, p, null));
                        String detail = interactionDetail(event);
                        store.putTimeline(new AppUsageStore.TimelineEntry(
                                "interaction:" + pkg + ":" + minute,
                                time, 0L, AppUsageStore.KIND_APP_INTERACTION, pkg, meta.label,
                                0L, meta.system, detail, null, null));
                        continue;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1
                            && type == UsageEvents.Event.SHORTCUT_INVOCATION && !TextUtils.isEmpty(pkg)) {
                        PackageMeta meta = packageMetaCache.computeIfAbsent(
                                pkg, p -> packageMeta(pm, p, null));
                        String detail = "App shortcut invoked";
                        if (!TextUtils.isEmpty(event.getShortcutId())) {
                            detail += " · " + event.getShortcutId();
                        }
                        store.putTimeline(new AppUsageStore.TimelineEntry(
                                "shortcut:" + pkg + ":" + time,
                                time, 0L, AppUsageStore.KIND_SHORTCUT, pkg, meta.label,
                                0L, meta.system, detail, null, null));
                    }
                }
            }

            if (windowEnd >= now) break;
            windowStart = windowEnd;
        }

        store.setMeta(META_LAST_EVENT_SYNC, now);
    }

"""
replace_between(path, start_marker, end_marker, new_method)


# 3) The warm icon bridge is deliberately kept during normal HOME transitions, but it is a
# rebuildable drawable cache and should be dropped when Android reports real memory pressure.
path = "app/src/main/java/fr/neamar/kiss/KissApplication.java"
replace_once(
    path,
    "import fr.neamar.kiss.update.AppUpdater;\nimport fr.neamar.kiss.utils.IconPackCache;\n",
    "import fr.neamar.kiss.update.AppUpdater;\nimport fr.neamar.kiss.utils.AppIconMemoryCache;\nimport fr.neamar.kiss.utils.IconPackCache;\n",
)
replace_once(
    path,
    "        if (runningCritical || backgroundSevere) {\n"
    "            mIconPackCache.clearCache(this);\n"
    "        }\n",
    "        if (runningCritical || backgroundSevere) {\n"
    "            AppIconMemoryCache.clear();\n"
    "            mIconPackCache.clearCache(this);\n"
    "        }\n",
)
replace_once(
    path,
    "        NotificationAvatarSupport.trimMemory(true);\n"
    "        mIconPackCache.clearCache(this);\n"
    "        mimeTypeCache.clearCache();\n",
    "        NotificationAvatarSupport.trimMemory(true);\n"
    "        AppIconMemoryCache.clear();\n"
    "        mIconPackCache.clearCache(this);\n"
    "        mimeTypeCache.clearCache();\n",
)


# 4) Every app change gets its own version. 3.30.59 / 487 will become baseline only after CI passes.
path = "app/build.gradle"
replace_once(
    path,
    "        // Smart S Launcher 3.30.58 - exact tap and long-press notification history routing\n"
    "        versionCode 486\n"
    "        versionName \"3.30.58\"\n",
    "        // Smart S Launcher 3.30.59 - bound runtime memory retention\n"
    "        versionCode 487\n"
    "        versionName \"3.30.59\"\n",
)
