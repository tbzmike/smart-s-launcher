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
import android.widget.EditText;
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
import fr.neamar.kiss.NotificationHistoryActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationHistorySearchPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.ui.CompactNotificationFrame;
import fr.neamar.kiss.ui.SmartAnimationEngine;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.NotificationHistoryResolver;
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
        CompactNotificationFrame nativeContainer = view.findViewById(R.id.item_notification_native_container);

        appName.setText(notification.appName);
        title.setText(notification.getSummary());

        boolean individualRecord = notification.id.startsWith(
                NotificationListener.NOTIFICATION_SCHEME);
        boolean groupRecord = notification.id.startsWith(
                NotificationListener.NOTIFICATION_GROUP_SCHEME);
        boolean individualActive = individualRecord
                && NotificationListener.isNotificationActive(context, notification.id);
        int activeGroupCount = groupRecord
                ? NotificationListener.getGroupNotifications(
                        context, notification.groupKey).size()
                : 0;
        NotificationActionPolicy.Target actionTarget = NotificationActionPolicy.resolve(
                individualRecord, groupRecord, individualActive, activeGroupCount);

        View.OnClickListener openGroup = actionTarget == NotificationActionPolicy.Target.NONE
                ? null : v -> showNotificationGroup(context, notification);
        nativeContainer.setInterceptChildTouches(true);
        nativeContainer.setOnClickListener(openGroup);

        View nativeView = null;
        if (actionTarget == NotificationActionPolicy.Target.INDIVIDUAL) {
            nativeView = NotificationListener.createNativeNotificationView(
                    context, notification.id, nativeContainer, false);
        } else if (actionTarget == NotificationActionPolicy.Target.GROUP) {
            nativeView = NotificationListener.createNativeGroupView(
                    context, notification.groupKey, nativeContainer, false);
        }
        if (nativeView != null) {
            nativeContainer.removeAllViews();
            nativeContainer.addView(nativeView);
            nativeContainer.setVisibility(View.VISIBLE);
        } else {
            nativeContainer.setVisibility(View.GONE);
        }

        String preview = notification.getPreview();
        if (preview.isEmpty()) preview = notification.getSummary();
        text.setText(preview);
        text.setVisibility(View.VISIBLE);

        if (!isHideIcons(context)) setAsyncDrawable(icon);
        else icon.setImageDrawable(null);

        // Only the app icon launches the app itself. IceBox-frozen apps are enabled first.
        icon.setOnClickListener(v -> {
            if (!AppLaunchUtils.launchPackage(context, notification.packageName)) {
                Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_SHORT).show();
            }
        });

        // Everything describing the notification opens Smart S's grouped popup.
        appName.setOnClickListener(openGroup);
        title.setOnClickListener(openGroup);
        text.setOnClickListener(openGroup);

        markRead.setText(R.string.notification_mark_read);
        if (actionTarget == NotificationActionPolicy.Target.NONE) {
            markRead.setVisibility(View.GONE);
            markRead.setEnabled(false);
            markRead.setOnClickListener(null);
            return view;
        }

        markRead.setVisibility(View.VISIBLE);
        markRead.setEnabled(true);
        markRead.setOnClickListener(v -> {
            boolean marked = actionTarget == NotificationActionPolicy.Target.INDIVIDUAL
                    ? NotificationListener.markNotificationRead(context, notification.id)
                    : NotificationListener.markGroupRead(context, notification.groupKey);
            if (marked) {
                markRead.setEnabled(false);
                view.setVisibility(View.GONE);
                context.sendBroadcast(MainActivity.internalBroadcast(context, MainActivity.LOAD_OVER));
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
            launchNotificationTarget(context, (NotificationPojo) pojo);
            return;
        }
        if (pojo instanceof DisabledAppPojo) {
            enableAndLaunch(context, (DisabledAppPojo) pojo);
            return;
        }
        if (pojo instanceof NotificationHistorySearchPojo) {
            NotificationHistorySearchPojo history = (NotificationHistorySearchPojo) pojo;
            Intent intent = new Intent(context, NotificationHistoryActivity.class);
            intent.putExtra(NotificationHistoryActivity.EXTRA_HISTORY_DB_ID, history.historyDbId);
            intent.putExtra(NotificationHistoryActivity.EXTRA_SEARCH_QUERY, history.searchQuery);
            intent.putExtra(NotificationHistoryActivity.EXTRA_PERMANENT, history.permanent);
            setSourceBounds(intent, v);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                launchSucceeded = true;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "Unable to open notification history search result", e);
                Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
            }
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

    private void launchNotificationTarget(Context context, NotificationPojo notification) {
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
        AlertDialog[] groupDialog = new AlertDialog[1];

        for (NotificationListener.NotificationSnapshot item : items) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, padding / 2, 0, padding / 2);
            View.OnClickListener openDetail = v -> showNotificationDetail(context, notification, item, groupDialog[0]);

            View nativeView = NotificationListener.createNativeNotificationView(context, item.id, row, false);
            if (nativeView != null) {
                if (nativeView instanceof CompactNotificationFrame) {
                    CompactNotificationFrame frame = (CompactNotificationFrame) nativeView;
                    frame.setInterceptChildTouches(true);
                    frame.setOnClickListener(openDetail);
                }
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

            row.setOnClickListener(openDetail);
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(notification.appName + " · " + notification.getSummary())
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null);
        boolean hasMarkAll = NotificationListener.hasMarkAllReadAction(context, notification.groupKey);
        if (hasMarkAll) builder.setPositiveButton("Mark all read", null);

        AlertDialog dialog = builder.create();
        groupDialog[0] = dialog;
        dialog.setOnShowListener(ignored -> {
            SmartAnimationEngine.animateDialogIn(dialog);
            if (hasMarkAll) {
                Button markAll = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                markAll.setOnClickListener(v -> {
                    if (NotificationListener.markAllRead(context, notification.groupKey)) {
                        context.sendBroadcast(MainActivity.internalBroadcast(context, MainActivity.LOAD_OVER));
                        SmartAnimationEngine.dismissDialog(dialog);
                    } else {
                        Toast.makeText(context, R.string.notification_dismiss_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
        dialog.show();
    }

    private void showNotificationDetail(Context context, NotificationPojo group,
                                        NotificationListener.NotificationSnapshot item,
                                        AlertDialog parentGroupDialog) {
        String detailTitle = item.title.isEmpty() ? group.appName : item.title;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 12);
        content.setPadding(padding, padding, padding, padding);

        View nativeView = NotificationListener.createNativeNotificationView(context, item.id, content, true);
        if (nativeView != null) {
            if (nativeView instanceof CompactNotificationFrame) {
                ((CompactNotificationFrame) nativeView).setInterceptChildTouches(true);
            }
            content.addView(nativeView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            TextView body = new TextView(context);
            body.setText(item.text.isEmpty() ? "No message text available." : item.text);
            body.setTextSize(16);
            content.addView(body);
        }

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button markRead = new Button(context);
        markRead.setText("Mark read");
        actions.addView(markRead, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button open = new Button(context);
        open.setText("Open notification");
        actions.addView(open, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (NotificationListener.hasReplyAction(context, item.id)) {
            Button reply = new Button(context);
            reply.setText("Reply");
            reply.setOnClickListener(v -> showReplyDialog(context, item));
            actions.addView(reply, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        content.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(detailTitle)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        markRead.setOnClickListener(v -> {
            if (!NotificationListener.markNotificationRead(context, item.id)) {
                Toast.makeText(context, R.string.notification_dismiss_failed, Toast.LENGTH_SHORT).show();
            } else {
                context.sendBroadcast(MainActivity.internalBroadcast(context, MainActivity.LOAD_OVER));
                SmartAnimationEngine.dismissDialog(dialog);
                if (parentGroupDialog != null && parentGroupDialog.isShowing()) {
                    SmartAnimationEngine.dismissDialog(parentGroupDialog);
                }
            }
        });

        open.setOnClickListener(v -> {
            if (!AppLaunchUtils.ensurePackageEnabled(context, group.packageName)) {
                Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!NotificationListener.openNotification(context, item.id)
                    && !AppLaunchUtils.launchPackage(context, group.packageName)) {
                Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_SHORT).show();
            }
        });

        dialog.setOnShowListener(ignored -> SmartAnimationEngine.animateDialogIn(dialog));
        dialog.show();
    }

    private void showReplyDialog(Context context, NotificationListener.NotificationSnapshot item) {
        EditText input = new EditText(context);
        input.setHint("Type a reply");
        input.setSingleLine(false);
        input.setMinLines(2);

        AlertDialog replyDialog = new AlertDialog.Builder(context)
                .setTitle("Reply")
                .setView(input)
                .setPositiveButton("Send", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        replyDialog.setOnShowListener(ignored -> {
            SmartAnimationEngine.animateDialogIn(replyDialog);
            replyDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String replyText = input.getText().toString().trim();
                if (replyText.isEmpty()) return;
                if (NotificationListener.replyToNotification(context, item.id, replyText)) {
                    SmartAnimationEngine.dismissDialog(replyDialog);
                } else {
                    Toast.makeText(context, "Unable to send reply.", Toast.LENGTH_SHORT).show();
                }
            });
        });
        replyDialog.show();
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
