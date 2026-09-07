from pathlib import Path
import subprocess


def blob(path: str) -> str:
    return subprocess.check_output(["git", "hash-object", path], text=True).strip()


def require_blob(path: str, expected: str) -> None:
    actual = blob(path)
    if actual != expected:
        raise SystemExit(f"{path}: expected blob {expected}, found {actual}")


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one guarded match, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


require_blob("app/src/main/java/fr/neamar/kiss/ui/TileLaunchCounter.java",
             "e5766b0fbe81bb55cb5541abe3f6ebb6fc357309")
require_blob("app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java",
             "3ed6f0a49c5aa82b2ed33476f233d385029fca0e")
require_blob("app/src/main/java/fr/neamar/kiss/result/AppResult.java",
             "527e517aa4f89df0880cff2c041ce216d340115c")
require_blob("app/src/main/java/fr/neamar/kiss/result/ShortcutsResult.java",
             "426174a4e7bbede710450e2f6ac6757ffac785e5")

Path("app/src/main/java/fr/neamar/kiss/ui/TileLaunchCounter.java").write_text(r'''package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;

/** Persistent click count for exact launcher tiles that are not represented by normal history. */
public final class TileLaunchCounter {
    private static final String PREFS = "smart-s-tile-launch-counts";
    private static final String COUNT_PREFIX = "count:";
    private static final Object LOCK = new Object();

    private TileLaunchCounter() {}

    /** Record one explicit click on an exact tile. */
    public static void record(@NonNull Context context, @NonNull Pojo pojo) {
        if (pojo instanceof NotificationPojo) {
            NotificationPojo notification = (NotificationPojo) pojo;
            recordNotification(context, notification.id, notification.postTime);
            return;
        }
        increment(context, storageKey(pojo));
    }

    /** Return the number of clicks recorded for this exact tile since tracking became available. */
    public static long getTotal(@NonNull Context context, @NonNull Pojo pojo) {
        if (pojo instanceof NotificationPojo) {
            NotificationPojo notification = (NotificationPojo) pojo;
            return getNotificationTotal(context, notification.id, notification.postTime);
        }
        return read(context, storageKey(pojo));
    }

    /** Record a click on the exact notification currently rendered inside an app/shortcut card. */
    public static void recordNotification(@NonNull Context context,
                                          String notificationId,
                                          long postTime) {
        increment(context, notificationStorageKey(notificationId, postTime));
    }

    /** Return clicks for one exact notification identity. */
    public static long getNotificationTotal(@NonNull Context context,
                                            String notificationId,
                                            long postTime) {
        return read(context, notificationStorageKey(notificationId, postTime));
    }

    private static void increment(@NonNull Context context, String key) {
        if (TextUtils.isEmpty(key)) return;
        synchronized (LOCK) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String countKey = COUNT_PREFIX + key;
            long current = Math.max(0L, prefs.getLong(countKey, 0L));
            long next = current == Long.MAX_VALUE ? Long.MAX_VALUE : current + 1L;
            prefs.edit().putLong(countKey, next).apply();
        }
    }

    private static long read(@NonNull Context context, String key) {
        if (TextUtils.isEmpty(key)) return 0L;
        synchronized (LOCK) {
            return Math.max(0L, context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(COUNT_PREFIX + key, 0L));
        }
    }

    /**
     * A notification ID can be reused by an app. Including post time keeps a newly posted
     * notification from inheriting the click count of an older notification with the same ID.
     */
    private static String notificationStorageKey(String notificationId, long postTime) {
        if (TextUtils.isEmpty(notificationId)) return "";
        return notificationId + "|post:" + Math.max(0L, postTime);
    }

    @NonNull
    static String storageKey(@NonNull Pojo pojo) {
        String historyId = pojo.getHistoryId();
        return historyId == null ? "" : historyId;
    }
}
''', encoding="utf-8")

replace_once(
    "app/src/main/java/fr/neamar/kiss/result/AppResult.java",
    "import fr.neamar.kiss.ui.NotificationPopupDialog;\nimport fr.neamar.kiss.utils.AppIconMemoryCache;",
    "import fr.neamar.kiss.ui.NotificationPopupDialog;\nimport fr.neamar.kiss.ui.TileLaunchCounter;\nimport fr.neamar.kiss.utils.AppIconMemoryCache;")
replace_once(
    "app/src/main/java/fr/neamar/kiss/result/AppResult.java",
    "        View.OnClickListener exactNotificationClick = v -> {\n"
    "            if (!NotificationListener.openLatestNotification(context, packageKey)) {\n"
    "                NotificationPopupDialog.showGroup(context, packageKey);\n"
    "            }\n"
    "        };",
    "        View.OnClickListener exactNotificationClick = v -> {\n"
    "            java.util.List<NotificationListener.NotificationSnapshot> active =\n"
    "                    NotificationListener.getGroupNotifications(context, packageKey);\n"
    "            if (!active.isEmpty()) {\n"
    "                NotificationListener.NotificationSnapshot latest = active.get(0);\n"
    "                TileLaunchCounter.recordNotification(context, latest.id, latest.postTime);\n"
    "            }\n"
    "            if (!NotificationListener.openLatestNotification(context, packageKey)) {\n"
    "                NotificationPopupDialog.showGroup(context, packageKey);\n"
    "            }\n"
    "        };")

replace_once(
    "app/src/main/java/fr/neamar/kiss/result/ShortcutsResult.java",
    "import fr.neamar.kiss.ui.NotificationPopupDialog;\nimport fr.neamar.kiss.utils.AppLaunchUtils;",
    "import fr.neamar.kiss.ui.NotificationPopupDialog;\nimport fr.neamar.kiss.ui.TileLaunchCounter;\nimport fr.neamar.kiss.utils.AppLaunchUtils;")
replace_once(
    "app/src/main/java/fr/neamar/kiss/result/ShortcutsResult.java",
    "            View.OnClickListener exactNotificationClick = v -> {\n"
    "                if (!NotificationListener.openLatestNotification(context, groupKey)) {\n"
    "                    NotificationPopupDialog.showGroup(context, groupKey);\n"
    "                }\n"
    "            };",
    "            View.OnClickListener exactNotificationClick = v -> {\n"
    "                TileLaunchCounter.recordNotification(\n"
    "                        context, latestActive.id, latestActive.postTime);\n"
    "                if (!NotificationListener.openLatestNotification(context, groupKey)) {\n"
    "                    NotificationPopupDialog.showGroup(context, groupKey);\n"
    "                }\n"
    "            };")
replace_once(
    "app/src/main/java/fr/neamar/kiss/result/ShortcutsResult.java",
    "                View.OnClickListener exactSavedNotificationClick = v -> {\n"
    "                    if (!SavedNotificationDestinationResolver.openExact(context, latestSaved)) {\n"
    "                        Toast.makeText(context, \"Unable to open this exact notification\", Toast.LENGTH_SHORT).show();\n"
    "                    }\n"
    "                };",
    "                View.OnClickListener exactSavedNotificationClick = v -> {\n"
    "                    TileLaunchCounter.recordNotification(\n"
    "                            context, latestSaved.notificationId, latestSaved.postTime);\n"
    "                    if (!SavedNotificationDestinationResolver.openExact(context, latestSaved)) {\n"
    "                        Toast.makeText(context, \"Unable to open this exact notification\", Toast.LENGTH_SHORT).show();\n"
    "                    }\n"
    "                };")

replace_once(
    "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java",
    "import java.util.HashMap;\nimport java.util.Map;",
    "import java.util.HashMap;\nimport java.util.List;\nimport java.util.Map;")
replace_once(
    "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java",
    "import fr.neamar.kiss.db.LaunchStatsProvider;\nimport fr.neamar.kiss.pojo.AppPojo;",
    "import fr.neamar.kiss.db.LaunchStatsProvider;\nimport fr.neamar.kiss.notification.NotificationListener;\nimport fr.neamar.kiss.pojo.AppPojo;")
replace_once(
    "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java",
    "            String metadataText = buildMetadata(\n"
    "                    pojo, packageName, stats, currentSnapshot, currentShortcutSnapshot);",
    "            String metadataText = buildMetadata(\n"
    "                    pojo, packageName, stats, currentSnapshot, currentShortcutSnapshot);")

old_block = '''    private String buildMetadata(Pojo pojo,\n                                 String packageName,\n                                 LaunchStatsProvider.LaunchStats stats,\n                                 AppUsageTodayStore.Snapshot currentSnapshot,\n                                 HistoryItemUsageTodayStore.Snapshot currentShortcutSnapshot) {\n        StringBuilder metadata = new StringBuilder();\n        appendMetadata(metadata, formatPostedTime(pojo, stats));\n\n        String usage = formatUsage(\n                pojo, packageName, currentSnapshot, currentShortcutSnapshot);\n        appendMetadata(metadata, usage);\n\n        long launches = pojo instanceof NotificationPojo\n                ? TileLaunchCounter.getTotal(mainActivity, pojo)\n                : stats == null ? 0L : Math.max(0, stats.totalLaunches);\n        appendMetadata(metadata, formatLaunchCount(launches));\n        return metadata.toString();\n    }\n\n    private String formatPostedTime(Pojo pojo, LaunchStatsProvider.LaunchStats stats) {\n        long timestamp = resolveTimestamp(pojo, stats);\n        String label = pojo instanceof NotificationPojo ? "Received" : "Posted";\n        if (timestamp <= 0L) return label + ": unavailable";\n\n        long now = System.currentTimeMillis();\n        String relative;\n        if (Math.abs(now - timestamp) < DateUtils.MINUTE_IN_MILLIS) {\n            relative = timestamp <= now ? "just now" : "in <1m";\n        } else {\n            relative = DateUtils.getRelativeTimeSpanString(\n                    timestamp,\n                    now,\n                    DateUtils.MINUTE_IN_MILLIS,\n                    DateUtils.FORMAT_ABBREV_RELATIVE).toString();\n        }\n\n        Date date = new Date(timestamp);\n        String exact = DateFormat.getTimeFormat(mainActivity).format(date);\n        if (!DateUtils.isToday(timestamp)) {\n            exact = DateFormat.getMediumDateFormat(mainActivity).format(date) + " " + exact;\n        }\n        return label + " " + relative + " · " + exact;\n    }\n\n    private long resolveTimestamp(Pojo pojo, LaunchStatsProvider.LaunchStats stats) {\n        if (pojo instanceof NotificationPojo) {\n            long postTime = ((NotificationPojo) pojo).postTime;\n            if (postTime > 0L) return postTime;\n        }\n        if (pojo instanceof CommunicationPojo) {\n            long eventTime = ((CommunicationPojo) pojo).timestamp;\n            if (eventTime > 0L) return eventTime;\n        }\n        return stats == null ? 0L : Math.max(0L, stats.lastLaunchTime);\n    }\n'''
new_block = '''    private String buildMetadata(Pojo pojo,\n                                 String packageName,\n                                 LaunchStatsProvider.LaunchStats stats,\n                                 AppUsageTodayStore.Snapshot currentSnapshot,\n                                 HistoryItemUsageTodayStore.Snapshot currentShortcutSnapshot) {\n        NotificationIdentity notification = resolveVisibleNotification(pojo, packageName);\n        StringBuilder metadata = new StringBuilder();\n        if (notification != null) {\n            appendMetadata(metadata, formatEventTime("Received", notification.postTime));\n        } else {\n            appendMetadata(metadata, formatEventTime("Posted", resolveHistoryTimestamp(pojo, stats)));\n        }\n\n        String usage = formatUsage(\n                pojo, packageName, currentSnapshot, currentShortcutSnapshot);\n        appendMetadata(metadata, usage);\n\n        long launches = notification != null\n                ? TileLaunchCounter.getNotificationTotal(\n                        mainActivity, notification.notificationId, notification.postTime)\n                : stats == null ? 0L : Math.max(0, stats.totalLaunches);\n        appendMetadata(metadata, formatLaunchCount(launches));\n        return metadata.toString();\n    }\n\n    private NotificationIdentity resolveVisibleNotification(Pojo pojo, String packageName) {\n        if (pojo instanceof NotificationPojo) {\n            NotificationPojo notification = (NotificationPojo) pojo;\n            return new NotificationIdentity(notification.id, notification.postTime);\n        }\n\n        String groupKey = null;\n        if (pojo instanceof AppPojo) {\n            groupKey = ((AppPojo) pojo).getPackageKey();\n        } else if (pojo instanceof ShortcutPojo && !TextUtils.isEmpty(packageName)) {\n            ShortcutPojo shortcut = (ShortcutPojo) pojo;\n            groupKey = shortcut.getUserHandle().getRealHandle().hashCode() + "|" + packageName;\n        }\n        if (TextUtils.isEmpty(groupKey)) return null;\n\n        List<NotificationListener.NotificationSnapshot> active =\n                NotificationListener.getGroupNotifications(mainActivity, groupKey);\n        if (active.isEmpty()) return null;\n        NotificationListener.NotificationSnapshot latest = active.get(0);\n        return new NotificationIdentity(latest.id, latest.postTime);\n    }\n\n    private String formatEventTime(String label, long timestamp) {\n        if (timestamp <= 0L) return label + ": unavailable";\n\n        long now = System.currentTimeMillis();\n        String relative;\n        if (Math.abs(now - timestamp) < DateUtils.MINUTE_IN_MILLIS) {\n            relative = timestamp <= now ? "just now" : "in <1m";\n        } else {\n            relative = DateUtils.getRelativeTimeSpanString(\n                    timestamp,\n                    now,\n                    DateUtils.MINUTE_IN_MILLIS,\n                    DateUtils.FORMAT_ABBREV_RELATIVE).toString();\n        }\n\n        Date date = new Date(timestamp);\n        String exact = DateFormat.getTimeFormat(mainActivity).format(date);\n        if (!DateUtils.isToday(timestamp)) {\n            exact = DateFormat.getMediumDateFormat(mainActivity).format(date) + " " + exact;\n        }\n        return label + " " + relative + " · " + exact;\n    }\n\n    private long resolveHistoryTimestamp(Pojo pojo, LaunchStatsProvider.LaunchStats stats) {\n        if (pojo instanceof CommunicationPojo) {\n            long eventTime = ((CommunicationPojo) pojo).timestamp;\n            if (eventTime > 0L) return eventTime;\n        }\n        return stats == null ? 0L : Math.max(0L, stats.lastLaunchTime);\n    }\n'''
replace_once("app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java",
             old_block, new_block)

replace_once(
    "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java",
    "    private static final class UsageView {\n",
    "    private static final class NotificationIdentity {\n"
    "        final String notificationId;\n"
    "        final long postTime;\n"
    "\n"
    "        NotificationIdentity(String notificationId, long postTime) {\n"
    "            this.notificationId = notificationId;\n"
    "            this.postTime = postTime;\n"
    "        }\n"
    "    }\n"
    "\n"
    "    private static final class UsageView {\n")

# Verify the exact behavior requested by the screenshot is present after patching.
vertical = Path("app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardUsageForwarder.java").read_text(encoding="utf-8")
for needle in [
    'resolveVisibleNotification(pojo, packageName)',
    'formatEventTime("Received", notification.postTime)',
    'getNotificationTotal(',
    'NotificationListener.getGroupNotifications(mainActivity, groupKey)',
    'formatEventTime("Posted", resolveHistoryTimestamp(pojo, stats))',
]:
    if needle not in vertical:
        raise SystemExit(f"Vertical card metadata invariant missing: {needle}")

app = Path("app/src/main/java/fr/neamar/kiss/result/AppResult.java").read_text(encoding="utf-8")
shortcut = Path("app/src/main/java/fr/neamar/kiss/result/ShortcutsResult.java").read_text(encoding="utf-8")
if app.count("TileLaunchCounter.recordNotification(context, latest.id, latest.postTime);") != 1:
    raise SystemExit("AppResult notification click counter is not wired exactly once")
if shortcut.count("TileLaunchCounter.recordNotification(") != 2:
    raise SystemExit("ShortcutsResult active/saved notification counters are not both wired")

print("Embedded notification timestamp/click metadata completion verified")
