from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# 1) Version bump: every source fix gets a new app version.
gradle_path = ROOT / "app/build.gradle"
gradle = gradle_path.read_text()
gradle = replace_once(
    gradle,
    '        // Smart S Launcher 3.30.83 - reusable exact notification destination routes\n'
    '        versionCode 511\n'
    '        versionName "3.30.83"',
    '        // Smart S Launcher 3.30.84 - distinct notification icon/message interactions\n'
    '        versionCode 512\n'
    '        versionName "3.30.84"',
    "app version",
)
gradle_path.write_text(gradle)


# 2) One shared interaction policy in RecordAdapter for BOTH Search and Home.
adapter_path = ROOT / "app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java"
adapter = adapter_path.read_text()
adapter = replace_once(
    adapter,
    'import android.widget.TextView;\n',
    'import android.widget.TextView;\nimport android.widget.Toast;\n',
    "RecordAdapter Toast import",
)
adapter = replace_once(
    adapter,
    'import fr.neamar.kiss.utils.Log;\n',
    'import fr.neamar.kiss.utils.AppLaunchUtils;\nimport fr.neamar.kiss.utils.Log;\n',
    "RecordAdapter AppLaunchUtils import",
)

start_marker = '    private void configureNotificationTileClick(View view, Result<?> result) {'
end_marker = '    private void recordExplicitSelection(Context context, Pojo pojo) {'
start = adapter.find(start_marker)
end = adapter.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("RecordAdapter notification click method boundaries not found")
old_method = adapter[start:end]
if 'R.id.item_notification_icon' not in old_method or 'child.setOnClickListener(openExactTarget);' not in old_method:
    raise SystemExit("RecordAdapter baseline no longer binds the notification icon to the exact target")

new_method = '''    private void configureNotificationTileClick(View view, Result<?> result) {
        if (!(result.getPojo() instanceof NotificationPojo)) return;
        NotificationPojo notification = (NotificationPojo) result.getPojo();

        View.OnClickListener openExactTarget = v -> {
            SearchHandler.getInstance().cancelSearch();
            RecentLaunchTracker.remember(result.getPojo());
            promoteHistoryResult(result);
            result.launch(v.getContext(), v, parent);
        };
        View.OnClickListener openApp = v -> openNotificationApp(result, notification, v);
        View.OnLongClickListener openRichNotification = v -> {
            int position = results.indexOf(result);
            if (position >= 0) {
                onLongClick(position, v);
                return true;
            }
            return false;
        };

        // Row/message taps open the exact notification destination. The icon is deliberately
        // excluded from this group and always performs a normal application launch instead.
        view.setOnClickListener(openExactTarget);
        view.setOnLongClickListener(openRichNotification);
        int[] messageIds = new int[]{R.id.item_notification_native_container,
                R.id.item_notification_app, R.id.item_notification_title,
                R.id.item_notification_text};
        for (int id : messageIds) {
            View child = view.findViewById(id);
            if (child != null) {
                child.setOnClickListener(openExactTarget);
                child.setOnLongClickListener(openRichNotification);
            }
        }

        View icon = view.findViewById(R.id.item_notification_icon);
        if (icon != null) {
            icon.setClickable(true);
            icon.setOnClickListener(openApp);
            // Long-pressing the icon still exposes the same notification popup as the message.
            icon.setOnLongClickListener(openRichNotification);
        }
    }

    /**
     * Normal app launch used by notification icons in every renderer. This intentionally does
     * not touch the exact saved notification destination, so an expired route cannot break an
     * ordinary app-icon tap.
     */
    private void openNotificationApp(Result<?> result, NotificationPojo notification, View source) {
        if (result == null || notification == null || source == null) return;
        SearchHandler.getInstance().cancelSearch();
        Context context = source.getContext();
        parent.externalResultLaunchStarting();
        boolean launched = AppLaunchUtils.launchPackage(context, notification.packageName);
        if (launched) {
            parent.externalResultLaunchOccurred();
        } else {
            parent.externalResultLaunchCancelled();
            Toast.makeText(context, "Unable to open " + notification.appName + ".",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Shared entry point for custom Home renderers so Search and Home use the same rule. */
    public void openNotificationApp(final int pos, View source) {
        if (pos < 0 || pos >= getCount() || source == null) return;
        Result<?> result = getItem(pos);
        if (!(result.getPojo() instanceof NotificationPojo)) {
            onClick(pos, source);
            return;
        }
        openNotificationApp(result, (NotificationPojo) result.getPojo(), source);
    }

'''
adapter = adapter[:start] + new_method + adapter[end:]
adapter_path.write_text(adapter)


# 3) Vertical Cards/Home must not overwrite the icon's shared app-launch listener.
forwarder_path = ROOT / "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardNotificationHistoryForwarder.java"
forwarder = forwarder_path.read_text()

start_marker = '    private void applyEasyIconTap(View wrapper, Result<?> result, String stableId) {'
end_marker = '    private int resolveAdapterPosition(String stableId) {'
start = forwarder.find(start_marker)
end = forwarder.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("VerticalCard applyEasyIconTap boundaries not found")
old_method = forwarder[start:end]
if 'DisabledAppPojo' not in old_method or 'mainActivity.adapter.onClick(currentPosition, wrapper);' not in old_method:
    raise SystemExit("VerticalCard easy-icon baseline changed unexpectedly")

new_method = '''    private void applyEasyIconTap(View wrapper, Result<?> result, String stableId) {
        if (wrapper == null || result == null || result.getPojo() == null) return;
        Pojo pojo = result.getPojo();
        if (!(pojo instanceof AppPojo)
                && !(pojo instanceof ShortcutPojo)
                && !(pojo instanceof DisabledAppPojo)
                && !(pojo instanceof NotificationPojo)) {
            return;
        }

        ImageView icon = findFirstVisibleImage(wrapper);
        if (icon == null) return;

        icon.setClickable(true);
        icon.setOnClickListener(v -> {
            int currentPosition = resolveAdapterPosition(stableId);
            if (currentPosition < 0) return;
            if (pojo instanceof NotificationPojo) {
                mainActivity.adapter.openNotificationApp(currentPosition, icon);
            } else {
                mainActivity.adapter.onClick(currentPosition, wrapper);
            }
        });

        if (!(icon.getParent() instanceof ViewGroup)) return;
        ViewGroup touchParent = (ViewGroup) icon.getParent();
        touchParent.post(() -> {
            if (icon.getParent() != touchParent || !icon.isShown()) return;
            Rect hit = new Rect();
            icon.getHitRect(hit);
            int extra = Math.round(18f * mainActivity.getResources().getDisplayMetrics().density);
            hit.left -= extra;
            hit.top -= extra;
            hit.right += extra;
            hit.bottom += extra;
            touchParent.setTouchDelegate(new TouchDelegate(hit, icon));
        });
    }

'''
forwarder = forwarder[:start] + new_method + forwarder[end:]

old_recursive = '''    private void applyNotificationClickRecursively(View view, View.OnClickListener listener) {
        if (view == null || view instanceof Button || isDetailsToggle(view)) return;
        view.setClickable(true);
        view.setOnClickListener(listener);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyNotificationClickRecursively(group.getChildAt(i), listener);
        }
    }
'''
new_recursive = '''    private void applyNotificationClickRecursively(View view, View.OnClickListener listener) {
        if (view == null || view instanceof Button || isDetailsToggle(view)) return;
        // The app icon has its own normal-launch action. Never replace it with the message route.
        if (view.getId() == fr.neamar.kiss.R.id.item_notification_icon) return;
        view.setClickable(true);
        view.setOnClickListener(listener);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyNotificationClickRecursively(group.getChildAt(i), listener);
        }
    }
'''
forwarder = replace_once(
    forwarder, old_recursive, new_recursive,
    "VerticalCard notification recursive click binding",
)
forwarder_path.write_text(forwarder)

print("Applied Smart S Launcher 3.30.84 notification interaction repair")
