from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1))

# Version bump.
replace_once("app/build.gradle",
'''        // Smart S Launcher 3.30.09
        versionCode 437
        versionName "3.30.09"''',
'''        // Smart S Launcher 3.30.10
        versionCode 438
        versionName "3.30.10"''')

# Exact Android/Oreo shortcuts must never silently degrade into opening only the publisher/target app.
p = Path("app/src/main/java/fr/neamar/kiss/result/ShortcutsResult.java")
text = p.read_text()
text = text.replace('''                launchShortcutFallbackApp(context);''', '''                notifyExactShortcutUnavailable(context);''')
text = text.replace('''        if (launchShortcutFallbackApp(context)) {
            launchSucceeded = true;
            return;
        }

        Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();''', '''        notifyExactShortcutUnavailable(context);''')
old = '''    private boolean launchShortcutFallbackApp(Context context) {
        String targetPackage = resolveTargetPackageName(context);
        if (!TextUtils.isEmpty(targetPackage) && AppLaunchUtils.launchPackage(context, targetPackage)) {
            return true;
        }
        return !TextUtils.equals(targetPackage, pojo.packageName)
                && AppLaunchUtils.launchPackage(context, pojo.packageName);
    }'''
new = '''    private void notifyExactShortcutUnavailable(Context context) {
        launchSucceeded = false;
        Toast.makeText(context, "Unable to open this exact shortcut.", Toast.LENGTH_LONG).show();
    }'''
if text.count(old) != 1:
    raise SystemExit("ShortcutsResult fallback block mismatch")
text = text.replace(old, new, 1)
# Embedded notification preview on a shortcut card should use the notification's own content intent first.
old = '''            View.OnClickListener popupClick = v -> NotificationPopupDialog.showGroup(context, groupKey);
            row.setOnClickListener(popupClick);
            text.setOnClickListener(popupClick);'''
new = '''            View.OnClickListener exactNotificationClick = v -> {
                if (!NotificationListener.openLatestNotification(context, groupKey)) {
                    NotificationPopupDialog.showGroup(context, groupKey);
                }
            };
            row.setOnClickListener(exactNotificationClick);
            text.setOnClickListener(exactNotificationClick);'''
if text.count(old) != 1:
    raise SystemExit("ShortcutsResult notification click block mismatch")
text = text.replace(old, new, 1)
p.write_text(text)

# Notification service: expose a single exact-launch helper for the newest notification in a group.
p = Path("app/src/main/java/fr/neamar/kiss/notification/NotificationListener.java")
text = p.read_text()
anchor = '''    public static boolean hasReplyAction(Context context, String notificationId) {'''
insert = '''    public static boolean openLatestNotification(Context context, String groupKey) {
        List<NotificationSnapshot> notifications = getGroupNotifications(context, groupKey);
        if (notifications.isEmpty()) return false;
        return openNotification(context, notifications.get(0).id);
    }

'''
if text.count(anchor) != 1 or "openLatestNotification(Context context" in text:
    raise SystemExit("NotificationListener insertion mismatch")
text = text.replace(anchor, insert + anchor, 1)
p.write_text(text)

# App-row notification preview: tap the actual notification destination, not Smart S's popup first.
p = Path("app/src/main/java/fr/neamar/kiss/result/AppResult.java")
text = p.read_text()
old = '''        View.OnClickListener popupClick = v -> NotificationPopupDialog.showGroup(context, packageKey);
        row.setOnClickListener(popupClick);
        text.setOnClickListener(popupClick);
        if (appName != null) appName.setOnClickListener(popupClick);'''
new = '''        View.OnClickListener exactNotificationClick = v -> {
            if (!NotificationListener.openLatestNotification(context, packageKey)) {
                NotificationPopupDialog.showGroup(context, packageKey);
            }
        };
        row.setOnClickListener(exactNotificationClick);
        text.setOnClickListener(exactNotificationClick);
        if (appName != null) appName.setOnClickListener(exactNotificationClick);'''
if text.count(old) != 1:
    raise SystemExit("AppResult notification click block mismatch")
text = text.replace(old, new, 1)
p.write_text(text)

# Contact results that represent a third-party contact row should open that exact row/action on tap.
p = Path("app/src/main/java/fr/neamar/kiss/result/ContactsResult.java")
text = p.read_text()
old = '''    public void doLaunch(Context context, View v) {
        SharedPreferences settingPrefs = PreferenceManager.getDefaultSharedPreferences(v.getContext());
        boolean callContactOnClick = settingPrefs.getBoolean("call-contact-on-click", false);

        if (callContactOnClick) {
            launchCall(context, v, pojo.phone);
        } else {
            launchContactView(context, v);
        }
    }'''
new = '''    public void doLaunch(Context context, View v) {
        // A third-party Contacts provider row (WhatsApp/Signal/etc.) is already an exact app
        // destination. Preserve it instead of replacing it with the generic Android contact card.
        if (pojo.getContactData() != null) {
            Intent exactContactIntent = MimeTypeUtils.getRegisteredIntentByMimeType(context,
                    pojo.getContactData().getMimeType(), pojo.getContactData().getId(),
                    pojo.getContactData().getIdentifier());
            if (exactContactIntent != null) {
                setSourceBounds(exactContactIntent, v);
                context.startActivity(exactContactIntent);
                return;
            }
        }

        SharedPreferences settingPrefs = PreferenceManager.getDefaultSharedPreferences(v.getContext());
        boolean callContactOnClick = settingPrefs.getBoolean("call-contact-on-click", false);

        if (callContactOnClick) {
            launchCall(context, v, pojo.phone);
        } else {
            launchContactView(context, v);
        }
    }'''
if text.count(old) != 1:
    raise SystemExit("ContactsResult doLaunch mismatch")
text = text.replace(old, new, 1)
p.write_text(text)

# Notification search/history results: exact live PendingIntent first; group popup/history only when no single exact target exists.
p = Path("app/src/main/java/fr/neamar/kiss/result/SettingsResult.java")
text = p.read_text()
if 'import fr.neamar.kiss.utils.NotificationHistoryResolver;' not in text:
    text = text.replace('''import fr.neamar.kiss.utils.Log;''', '''import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.NotificationHistoryResolver;''', 1)
old = '''        if (pojo instanceof NotificationPojo) {
            showNotificationGroup(context, (NotificationPojo) pojo);
            return;
        }'''
new = '''        if (pojo instanceof NotificationPojo) {
            launchNotificationTarget(context, (NotificationPojo) pojo);
            return;
        }'''
if text.count(old) != 1:
    raise SystemExit("SettingsResult notification doLaunch mismatch")
text = text.replace(old, new, 1)
anchor = '''    private void showNotificationGroup(Context context, NotificationPojo notification) {'''
insert = '''    private void launchNotificationTarget(Context context, NotificationPojo notification) {
        boolean individual = notification.id.startsWith(NotificationListener.NOTIFICATION_SCHEME);
        if (individual && NotificationListener.isNotificationActive(context, notification.id)
                && NotificationListener.openNotification(context, notification.id)) {
            launchSucceeded = true;
            return;
        }

        List<NotificationListener.NotificationSnapshot> active =
                NotificationListener.getGroupNotifications(context, notification.groupKey);
        if (active.size() == 1 && NotificationListener.openNotification(context, active.get(0).id)) {
            launchSucceeded = true;
            return;
        }
        if (!active.isEmpty()) {
            showNotificationGroup(context, notification);
            return;
        }

        if (!NotificationHistoryResolver.showForPojo(context, notification)) {
            Toast.makeText(context, "No exact notification destination is available.",
                    Toast.LENGTH_SHORT).show();
        }
    }

'''
if text.count(anchor) != 1 or "launchNotificationTarget(Context context" in text:
    raise SystemExit("SettingsResult insertion mismatch")
text = text.replace(anchor, insert + anchor, 1)
p.write_text(text)

# Result click interception: tap should launch the result; notification history remains a long-press action.
p = Path("app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java")
text = p.read_text()
old = '''        View.OnClickListener openHistory = v -> {
            SearchHandler.getInstance().cancelSearch();
            RecentLaunchTracker.remember(result.getPojo());
            promoteHistoryResult(result);
            if (NotificationHistoryResolver.showForPojo(v.getContext(), result.getPojo())) {
                recordExplicitSelection(v.getContext(), result.getPojo());
                return;
            }
            result.launch(v.getContext(), v, parent);
        };'''
new = '''        View.OnClickListener openExactTarget = v -> {
            SearchHandler.getInstance().cancelSearch();
            RecentLaunchTracker.remember(result.getPojo());
            promoteHistoryResult(result);
            result.launch(v.getContext(), v, parent);
        };'''
if text.count(old) != 1:
    raise SystemExit("RecordAdapter notification listener mismatch")
text = text.replace(old, new, 1)
text = text.replace('''        view.setOnClickListener(openHistory);''', '''        view.setOnClickListener(openExactTarget);''', 1)
text = text.replace('''                child.setOnClickListener(openHistory);''', '''                child.setOnClickListener(openExactTarget);''', 1)
old = '''            if (result.getPojo() instanceof NotificationPojo
                    && NotificationHistoryResolver.showForPojo(v.getContext(), result.getPojo())) {
                recordExplicitSelection(v.getContext(), result.getPojo());
                return;
            }

            Pojo pojo = result.getPojo();'''
new = '''            Pojo pojo = result.getPojo();'''
if text.count(old) != 1:
    raise SystemExit("RecordAdapter generic notification interceptor mismatch")
text = text.replace(old, new, 1)
p.write_text(text)

# Notification dialog's explicit Open button must not degrade to generic parent-app launch.
p = Path("app/src/main/java/fr/neamar/kiss/ui/NotificationPopupDialog.java")
text = p.read_text()
text = text.replace('''import fr.neamar.kiss.utils.AppLaunchUtils;\n''', '')
old = '''                    boolean opened = NotificationListener.openNotification(context, snapshot.id);
                    if (!opened && packageName != null) {
                        opened = AppLaunchUtils.launchPackage(context, packageName);
                    }
                    if (opened) {'''
new = '''                    boolean opened = NotificationListener.openNotification(context, snapshot.id);
                    if (opened) {'''
if text.count(old) != 1:
    raise SystemExit("NotificationPopupDialog fallback mismatch")
text = text.replace(old, new, 1)
p.write_text(text)
