package fr.neamar.kiss.result;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public class SettingsResult extends Result<SettingPojo> {
    private static final String TAG = SettingsResult.class.getSimpleName();
    private static final int ANDROID_UID_USER_RANGE = 100000;
    private static final String FEATURE_SCHEME = "feature://";
    private static final String HIDDEN_TARGETS = "hidden-launch-targets";
    private boolean launchSucceeded;

    SettingsResult(@NonNull SettingPojo pojo) {
        super(pojo);
    }

    @NonNull
    @Override
    public View display(Context context, View view, @NonNull ViewGroup parent, FuzzyScore fuzzyScore) {
        if (pojo instanceof NotificationPojo) {
            return displayNotificationGroup(context, parent, (NotificationPojo) pojo);
        }

        if (view == null || view.findViewById(R.id.item_setting_name) == null) {
            view = inflateFromId(context, R.layout.item_setting, parent);
        }

        TextView prefix = view.findViewById(R.id.item_setting_prefix);
        if (pojo instanceof DisabledAppPojo) prefix.setText("Disabled app:");
        else if (pojo.id.startsWith(FEATURE_SCHEME)) prefix.setText("Feature:");
        else prefix.setText(R.string.settings_prefix);

        TextView settingName = view.findViewById(R.id.item_setting_name);
        displayHighlighted(pojo.normalizedName, pojo.getName(), fuzzyScore, settingName, context);

        ImageView settingIcon = view.findViewById(R.id.item_setting_icon);
        if (!isHideIcons(context)) setAsyncDrawable(settingIcon);
        else settingIcon.setImageDrawable(null);
        return view;
    }

    private View displayNotificationGroup(Context context, ViewGroup parent, NotificationPojo notification) {
        View view = inflateFromId(context, R.layout.item_notification_timeline, parent);
        TextView appName = view.findViewById(R.id.item_notification_app);
        TextView title = view.findViewById(R.id.item_notification_title);
        TextView text = view.findViewById(R.id.item_notification_text);
        Button markRead = view.findViewById(R.id.item_notification_dismiss);
        ImageView icon = view.findViewById(R.id.item_notification_icon);
        FrameLayout nativeContainer = view.findViewById(R.id.item_notification_native_container);

        appName.setText(notification.appName);
        title.setText(notification.getSummary());

        View nativeView = NotificationListener.createNativeGroupView(context, notification.groupKey, nativeContainer, false);
        if (nativeView != null) {
            nativeContainer.removeAllViews();
            nativeContainer.addView(nativeView);
            nativeContainer.setVisibility(View.VISIBLE);
            text.setVisibility(View.GONE);
        } else {
            String preview = notification.latestTitle;
            if (!notification.latestText.isEmpty()) {
                preview = preview.isEmpty() ? notification.latestText : preview + ": " + notification.latestText;
            }
            text.setText(preview);
            text.setVisibility(preview.isEmpty() ? View.GONE : View.VISIBLE);
            nativeContainer.setVisibility(View.GONE);
        }

        if (!isHideIcons(context)) setAsyncDrawable(icon);
        else icon.setImageDrawable(null);

        markRead.setText(R.string.notification_mark_read);
        markRead.setOnClickListener(v -> {
            if (NotificationListener.markGroupRead(context, notification.groupKey)) {
                markRead.setEnabled(false);
                context.sendBroadcast(new Intent(MainActivity.LOAD_OVER));
            } else {
                Toast.makeText(context, R.string.notification_dismiss_failed, Toast.LENGTH_SHORT).show();
            }
        });
        return view;
    }

    @Override
    public Drawable getDrawable(Context context) {
        if (pojo instanceof NotificationPojo) {
            try {
                return context.getPackageManager().getApplicationIcon(((NotificationPojo) pojo).packageName);
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }
        if (pojo instanceof DisabledAppPojo) {
            DisabledAppPojo disabled = (DisabledAppPojo) pojo;
            try {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(disabled.targetPackage, PackageManager.GET_DISABLED_COMPONENTS);
                Drawable icon = info.loadIcon(context.getPackageManager());
                if (icon != null) icon.setAlpha(140);
                return icon;
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }
        if (pojo.icon != -1) return getThemedDrawable(context, pojo, pojo.icon);
        return null;
    }

    @Override
    public void doLaunch(Context context, View v) {
        launchSucceeded = false;
        if (pojo instanceof NotificationPojo) {
            showNotificationGroup(context, (NotificationPojo) pojo);
            return;
        }
        if (pojo instanceof DisabledAppPojo) {
            enableAndLaunch(context, (DisabledAppPojo) pojo);
            return;
        }

        Intent intent = new Intent(pojo.settingName);
        if (!pojo.packageName.isEmpty()) intent.setClassName(pojo.packageName, pojo.settingName);
        setSourceBounds(intent, v);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (pojo.id.startsWith(FEATURE_SCHEME) && !isFeatureLaunchableNow(context, intent)) {
            hideFailedTarget(context);
            Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            context.startActivity(intent);
            launchSucceeded = true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Unable to launch activity", e);
            hideFailedTarget(context);
            Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
        }
    }

    private void showNotificationGroup(Context context, NotificationPojo notification) {
        List<NotificationListener.NotificationSnapshot> items = NotificationListener.getGroupNotifications(context, notification.groupKey);
        if (items.isEmpty()) {
            Toast.makeText(context, "No active notifications.", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 16);
        list.setPadding(padding, padding / 2, padding, padding / 2);

        for (NotificationListener.NotificationSnapshot item : items) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, padding / 2, 0, padding / 2);

            View nativeView = NotificationListener.createNativeNotificationView(context, item.id, row, false);
            if (nativeView != null) {
                row.addView(nativeView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                TextView itemTitle = new TextView(context);
                itemTitle.setText(item.title.isEmpty() ? notification.appName : item.title);
                itemTitle.setTextSize(16);
                itemTitle.setTypeface(itemTitle.getTypeface(), android.graphics.Typeface.BOLD);
                row.addView(itemTitle);

                if (!item.text.isEmpty()) {
                    TextView body = new TextView(context);
                    body.setText(item.text);
                    body.setMaxLines(2);
                    body.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    body.setTextSize(14);
                    row.addView(body);
                }
            }

            row.setOnClickListener(v -> showNotificationDetail(context, notification, item));
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        new AlertDialog.Builder(context)
                .setTitle(notification.appName + " · " + notification.getSummary())
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showNotificationDetail(Context context, NotificationPojo group, NotificationListener.NotificationSnapshot item) {
        String title = item.title.isEmpty() ? group.appName : item.title;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 12);
        content.setPadding(padding, padding, padding, padding);

        View nativeView = NotificationListener.createNativeNotificationView(context, item.id, content, true);
        if (nativeView != null) {
            content.addView(nativeView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            TextView body = new TextView(context);
            body.setText(item.text.isEmpty() ? "No message text available." : item.text);
            body.setTextSize(16);
            content.addView(body);
        }

        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(content)
                .setPositiveButton("Open notification", (dialog, which) -> {
                    if (!NotificationListener.openNotification(context, item.id)) {
                        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(group.packageName);
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(launchIntent);
                        } else {
                            Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNeutralButton("Mark read", (dialog, which) -> {
                    if (!NotificationListener.markNotificationRead(context, item.id)) {
                        Toast.makeText(context, R.string.notification_dismiss_failed, Toast.LENGTH_SHORT).show();
                    } else {
                        context.sendBroadcast(new Intent(MainActivity.LOAD_OVER));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void hideFailedTarget(Context context) {
        if (pojo instanceof DisabledAppPojo) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> hidden = new HashSet<>(prefs.getStringSet(HIDDEN_TARGETS, java.util.Collections.emptySet()));
        hidden.add(pojo.id);
        prefs.edit().putStringSet(HIDDEN_TARGETS, hidden).apply();
        removeFromHistory(context);
    }

    private boolean isFeatureLaunchableNow(Context context, Intent intent) {
        PackageManager pm = context.getPackageManager();
        ResolveInfo resolved = pm.resolveActivity(intent, 0);
        if (resolved == null || resolved.activityInfo == null) return false;
        ActivityInfo activity = resolved.activityInfo;
        if (!activity.exported || !activity.enabled || activity.applicationInfo == null || !activity.applicationInfo.enabled) return false;
        return activity.permission == null || activity.permission.isEmpty()
                || context.checkCallingOrSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void enableAndLaunch(Context context, DisabledAppPojo disabled) {
        if (!KissApplication.getApplication(context).getRootHandler().isRootActivated()) {
            Toast.makeText(context, "Enable Root mode in Smart S Launcher settings first.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!KissApplication.getApplication(context).getRootHandler().isRootAvailable()) {
            Toast.makeText(context, "Root access is not available.", Toast.LENGTH_LONG).show();
            return;
        }

        int userId = Process.myUid() / ANDROID_UID_USER_RANGE;
        if (!KissApplication.getApplication(context).getRootHandler().enableApp(disabled.targetPackage, userId)) {
            Toast.makeText(context, "Unable to enable " + disabled.getName(), Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(disabled.targetPackage, disabled.activityName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            context.startActivity(intent);
            launchSucceeded = true;
            KissApplication.getApplication(context).getDataHandler().reloadApps();
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "App enabled but launcher activity could not be started", e);
            KissApplication.getApplication(context).getDataHandler().reloadApps();
            Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected boolean canAddToHistory() {
        if (pojo instanceof NotificationPojo) return false;
        return launchSucceeded;
    }

    @Override
    protected boolean isAllowedAsFavorite() {
        return !(pojo instanceof DisabledAppPojo) && !(pojo instanceof NotificationPojo);
    }

    @Override
    protected boolean canRemoveFromHistory(Context context) {
        return !(pojo instanceof NotificationPojo);
    }

    @Override
    protected boolean canHaveCustomIcon(Context context, IconPack iconPack) {
        return !(pojo instanceof DisabledAppPojo) && !(pojo instanceof NotificationPojo);
    }
}
