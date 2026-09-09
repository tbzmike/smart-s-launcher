from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1))


history = "app/src/main/java/fr/neamar/kiss/forwarder/HistoryDisplayForwarder.java"
replace_once(
    history,
    '''            View source = mainActivity.adapter.getView(position, reusable, mainActivity.list);\n\n            if (source != existing) {\n                if (existing != null) wheelColumn.removeViewAt(position);\n                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(\n                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);\n                lp.setMargins(dp(3), dp(4), dp(3), dp(4));\n                wheelColumn.addView(source, position, lp);\n            }\n''',
    '''            View source = mainActivity.adapter.getView(position, reusable, mainActivity.list);\n\n            // The adapter intentionally receives the real ListView as its rendering parent so the\n            // wheel keeps the same row styling/features as Vertical History. During a recycled bind\n            // RecordAdapter may therefore install AbsListView.LayoutParams on this View. The View is\n            // physically owned by wheelColumn, so normalize it back to LinearLayout.LayoutParams on\n            // every bind. Without this, a later LinearLayout measure can ClassCastException.\n            ViewGroup.LayoutParams rebound = source.getLayoutParams();\n            int reboundHeight = rebound == null\n                    ? ViewGroup.LayoutParams.WRAP_CONTENT : rebound.height;\n            LinearLayout.LayoutParams wheelParams = new LinearLayout.LayoutParams(\n                    ViewGroup.LayoutParams.MATCH_PARENT, reboundHeight);\n            wheelParams.setMargins(dp(3), dp(4), dp(3), dp(4));\n            wheelParams.gravity = Gravity.CENTER_HORIZONTAL;\n\n            if (source != existing) {\n                if (existing != null) wheelColumn.removeViewAt(position);\n                wheelColumn.addView(source, position, wheelParams);\n            } else {\n                source.setLayoutParams(wheelParams);\n            }\n''')

replace_once(
    history,
    '''        for (int i = 0; i < wheelColumn.getChildCount(); i++) {\n            View child = wheelColumn.getChildAt(i);\n            if (child.getBottom() < viewportTop - overscan\n                    || child.getTop() > viewportBottom + overscan) {\n                // Keep off-screen rows laid out for ScrollView geometry, but skip perspective work\n                // until they approach the viewport.\n                child.setAlpha(0.12f);\n                child.setRotationX(0f);\n                child.setScaleX(0.82f);\n                child.setScaleY(0.82f);\n                child.setTranslationY(0f);\n                child.setTranslationZ(0f);\n                continue;\n            }\n            float childCenter = child.getTop() + child.getHeight() / 2f;\n''',
    '''        // Horizontal Icons is smooth because scrolling does not rewrite every item in its\n        // complete data set. Apply the same principle here: the wheel keeps every row laid out for\n        // ScrollView geometry, while perspective properties are touched only for the near-visible\n        // window. A row is recalculated before it enters the viewport, so no 3D feature is removed.\n        int childCount = wheelColumn.getChildCount();\n        int first = firstWheelChildNear(viewportTop - overscan);\n        for (int i = first; i < childCount; i++) {\n            View child = wheelColumn.getChildAt(i);\n            if (child.getTop() > viewportBottom + overscan) break;\n            float childCenter = child.getTop() + child.getHeight() / 2f;\n''')

replace_once(
    history,
    '''    private void resetWheelTransforms() {\n''',
    '''    private int firstWheelChildNear(float minimumBottom) {\n        if (wheelColumn == null) return 0;\n        int count = wheelColumn.getChildCount();\n        int low = 0;\n        int high = count - 1;\n        int result = count;\n        while (low <= high) {\n            int mid = (low + high) >>> 1;\n            View child = wheelColumn.getChildAt(mid);\n            if (child.getBottom() >= minimumBottom) {\n                result = mid;\n                high = mid - 1;\n            } else {\n                low = mid + 1;\n            }\n        }\n        return Math.max(0, Math.min(count, result));\n    }\n\n    private void resetWheelTransforms() {\n''')

notification = "app/src/main/java/fr/neamar/kiss/notification/NotificationListener.java"
replace_once(
    notification,
    '''    private static final ExecutorService RECONCILE_EXECUTOR = Executors.newSingleThreadExecutor();\n''',
    '''    private static final ExecutorService RECONCILE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {\n        Thread thread = new Thread(r, "smart-s-notification-reconcile");\n        thread.setPriority(Thread.MIN_PRIORITY);\n        return thread;\n    });\n''')

replace_once(
    notification,
    '''    private static volatile boolean activeStateVerified;\n    private SharedPreferences prefs;\n''',
    '''    private static volatile boolean activeStateVerified;\n    private static final Object GROUP_SNAPSHOT_LOCK = new Object();\n    private static volatile Map<String, List<NotificationSnapshot>> verifiedGroupSnapshots =\n            Collections.emptyMap();\n    private static volatile long notificationStateGeneration;\n    private static volatile long verifiedGroupSnapshotGeneration = -1L;\n    private SharedPreferences prefs;\n''')

replace_once(
    notification,
    '''    public static boolean hasActiveNotificationGroup(Context context, String packageKey) {\n        if (packageKey == null || packageKey.isEmpty()) return false;\n        SharedPreferences cache = context.getSharedPreferences(\n                DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);\n        for (String id : getVerifiedActiveNotificationIds()) {\n            if (packageKey.equals(cache.getString(id + "|group", ""))) return true;\n        }\n        return false;\n    }\n''',
    '''    public static boolean hasActiveNotificationGroup(Context context, String packageKey) {\n        if (packageKey == null || packageKey.isEmpty()) return false;\n        return verifiedGroupSnapshots(context).containsKey(packageKey);\n    }\n''')

replace_once(
    notification,
    '''    public static List<NotificationSnapshot> getGroupNotifications(Context context, String groupKey) {\n        SharedPreferences details = context.getSharedPreferences(DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);\n        Set<String> active = getVerifiedActiveNotificationIds();\n        if (active.isEmpty()) return Collections.emptyList();\n        List<NotificationSnapshot> result = new ArrayList<>();\n        for (String id : new HashSet<>(active)) {\n            if (!groupKey.equals(details.getString(id + "|group", ""))) continue;\n            String title = details.getString(id + "|title", "");\n            String text = details.getString(id + "|text", "");\n            result.add(new NotificationSnapshot(id, title == null ? "" : title, text == null ? "" : text,\n                    details.getLong(id + "|post", 0L)));\n        }\n        result.sort(Comparator.comparingLong((NotificationSnapshot n) -> n.postTime).reversed());\n        return result;\n    }\n''',
    '''    public static List<NotificationSnapshot> getGroupNotifications(Context context, String groupKey) {\n        if (groupKey == null || groupKey.isEmpty()) return Collections.emptyList();\n        List<NotificationSnapshot> group = verifiedGroupSnapshots(context).get(groupKey);\n        return group == null ? Collections.emptyList() : group;\n    }\n\n    private static Map<String, List<NotificationSnapshot>> verifiedGroupSnapshots(Context context) {\n        long generation = notificationStateGeneration;\n        Map<String, List<NotificationSnapshot>> snapshot = verifiedGroupSnapshots;\n        if (verifiedGroupSnapshotGeneration == generation) return snapshot;\n\n        synchronized (GROUP_SNAPSHOT_LOCK) {\n            generation = notificationStateGeneration;\n            if (verifiedGroupSnapshotGeneration == generation) return verifiedGroupSnapshots;\n\n            SharedPreferences details = context.getSharedPreferences(\n                    DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);\n            Set<String> active = getVerifiedActiveNotificationIds();\n            if (active.isEmpty()) {\n                verifiedGroupSnapshots = Collections.emptyMap();\n                verifiedGroupSnapshotGeneration = generation;\n                return verifiedGroupSnapshots;\n            }\n\n            Map<String, List<NotificationSnapshot>> groups = new HashMap<>();\n            for (String id : active) {\n                String groupKey = details.getString(id + "|group", "");\n                if (groupKey == null || groupKey.isEmpty()) continue;\n                String title = details.getString(id + "|title", "");\n                String text = details.getString(id + "|text", "");\n                NotificationSnapshot item = new NotificationSnapshot(\n                        id, title == null ? "" : title, text == null ? "" : text,\n                        details.getLong(id + "|post", 0L));\n                groups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(item);\n            }\n\n            Map<String, List<NotificationSnapshot>> immutable = new HashMap<>();\n            for (Map.Entry<String, List<NotificationSnapshot>> entry : groups.entrySet()) {\n                List<NotificationSnapshot> items = entry.getValue();\n                items.sort(Comparator.comparingLong(\n                        (NotificationSnapshot n) -> n.postTime).reversed());\n                immutable.put(entry.getKey(), Collections.unmodifiableList(items));\n            }\n            verifiedGroupSnapshots = Collections.unmodifiableMap(immutable);\n            verifiedGroupSnapshotGeneration = generation;\n            return verifiedGroupSnapshots;\n        }\n    }\n\n    private static void invalidateVerifiedGroupSnapshots() {\n        synchronized (GROUP_SNAPSHOT_LOCK) {\n            notificationStateGeneration++;\n            verifiedGroupSnapshots = Collections.emptyMap();\n            verifiedGroupSnapshotGeneration = -1L;\n        }\n    }\n''')

replace_once(
    notification,
    '''    private static synchronized void publishVerifiedActiveIds(Set<String> ids) {\n        verifiedActiveIds = Collections.unmodifiableSet(new HashSet<>(ids));\n        activeStateVerified = true;\n    }\n\n    private static synchronized void publishUnverifiedActiveState() {\n        activeStateVerified = false;\n        verifiedActiveIds = Collections.emptySet();\n    }\n\n    private static synchronized void addVerifiedActiveId(String id) {\n        if (!activeStateVerified || id == null || id.isEmpty()) return;\n        Set<String> updated = new HashSet<>(verifiedActiveIds);\n        updated.add(id);\n        verifiedActiveIds = Collections.unmodifiableSet(updated);\n    }\n\n    private static synchronized void removeVerifiedActiveId(String id) {\n        if (!activeStateVerified || id == null || id.isEmpty()) return;\n        Set<String> updated = new HashSet<>(verifiedActiveIds);\n        updated.remove(id);\n        verifiedActiveIds = Collections.unmodifiableSet(updated);\n    }\n''',
    '''    private static synchronized void publishVerifiedActiveIds(Set<String> ids) {\n        verifiedActiveIds = Collections.unmodifiableSet(new HashSet<>(ids));\n        activeStateVerified = true;\n        invalidateVerifiedGroupSnapshots();\n    }\n\n    private static synchronized void publishUnverifiedActiveState() {\n        activeStateVerified = false;\n        verifiedActiveIds = Collections.emptySet();\n        invalidateVerifiedGroupSnapshots();\n    }\n\n    private static synchronized void addVerifiedActiveId(String id) {\n        if (!activeStateVerified || id == null || id.isEmpty()) return;\n        Set<String> updated = new HashSet<>(verifiedActiveIds);\n        updated.add(id);\n        verifiedActiveIds = Collections.unmodifiableSet(updated);\n        invalidateVerifiedGroupSnapshots();\n    }\n\n    private static synchronized void removeVerifiedActiveId(String id) {\n        if (!activeStateVerified || id == null || id.isEmpty()) return;\n        Set<String> updated = new HashSet<>(verifiedActiveIds);\n        updated.remove(id);\n        verifiedActiveIds = Collections.unmodifiableSet(updated);\n        invalidateVerifiedGroupSnapshots();\n    }\n''')

media = "app/src/main/java/fr/neamar/kiss/notification/MediaHistoryCoordinator.java"
replace_once(
    media,
    '''    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();\n''',
    '''    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {\n        Thread thread = new Thread(r, "smart-s-media-history");\n        thread.setPriority(Thread.MIN_PRIORITY);\n        return thread;\n    });\n''')

visual = "app/src/main/java/fr/neamar/kiss/notification/NotificationVisualSupport.java"
replace_once(
    visual,
    '''    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();\n''',
    '''    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {\n        Thread thread = new Thread(r, "smart-s-notification-visual");\n        thread.setPriority(Thread.MIN_PRIORITY);\n        return thread;\n    });\n''')

build = "app/build.gradle"
replace_once(
    build,
    '''        // Smart S Launcher 3.30.75 - selectable auto-scroll or full-text expanding tiles\n        versionCode 503\n        versionName "3.30.75"\n''',
    '''        // Smart S Launcher 3.30.76 - horizontal-baseline scrolling and stable 3D wheel\n        versionCode 504\n        versionName "3.30.76"\n''')

print("3.30.76 guarded performance patch applied")
