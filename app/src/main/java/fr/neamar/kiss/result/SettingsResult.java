package fr.neamar.kiss.result;

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
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.util.HashSet;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.NotificationHistoryActivity;
import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.NotificationTimelineStore;
import fr.neamar.kiss.R;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.notification.NotificationAvatarSupport;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationHistorySearchPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.ui.CompactNotificationFrame;
import fr.neamar.kiss.utils.AppReinstallSupport;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.NotificationHistoryResolver;
import fr.neamar.kiss.utils.SavedNotificationDestinationResolver;
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

        String exactNotificationId = notification.exactNotificationId;
        boolean exactActive = exactNotificationId.startsWith(
                NotificationListener.NOTIFICATION_SCHEME)
                && NotificationListener.isNotificationActive(
                context, exactNotificationId, notification.postTime);
        View.OnClickListener openExact = v -> launchNotificationTarget(context, notification);
        nativeContainer.setInterceptChildTouches(true);
        nativeContainer.setOnClickListener(openExact);

        View nativeView = exactActive
                ? NotificationListener.createNativeNotificationView(
                context, exactNotificationId, nativeContainer, false) : null;
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

        String avatarNotificationId = exactNotificationId;
        Drawable avatar = NotificationAvatarSupport.avatar(context, avatarNotificationId);
        if (!isHideIcons(context)) {
            if (avatar != null) icon.setImageDrawable(avatar);
            else setAsyncDrawable(icon);
        } else icon.setImageDrawable(null);

        // Every clickable part resolves the same exact child notification. No app-level or group
        // popup is reachable from a normal tap; popups are reserved for the long-press path.
        icon.setOnClickListener(openExact);
        appName.setOnClickListener(openExact);
        title.setOnClickListener(openExact);
        text.setOnClickListener(openExact);

        markRead.setText(R.string.notification_mark_read);
        if (!exactActive) {
            markRead.setVisibility(View.GONE);
            markRead.setEnabled(false);
            markRead.setOnClickListener(null);
            return view;
        }

        markRead.setVisibility(View.VISIBLE);
        markRead.setEnabled(true);
        markRead.setOnClickListener(v -> {
            boolean marked = NotificationListener.markNotificationRead(
                    context, exactNotificationId);
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
            NotificationHistoryRecord record = NotificationTimelineStore.findByDbId(
                    context, history.historyDbId);
            if (record != null) {
                SavedNotificationDestinationResolver.OpenResult result =
                        SavedNotificationDestinationResolver.openExactResult(context, record);
                if (handleExactNotificationResult(context, record.appName,
                        record.packageName, result)) return;
            }
            // The message is still retained even when Android never exposed a durable app route.
            // Fall back to the exact saved row instead of claiming the notification was deleted.
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
        // Use the persisted exact row first in every mode. This path distinguishes frozen from
        // uninstalled packages and enables a frozen package before attempting its saved route.
        SavedNotificationDestinationResolver.OpenResult exactResult =
                NotificationHistoryResolver.openExactResultForPojo(context, notification);
        if (handleExactNotificationResult(context, notification.appName,
                notification.packageName, exactResult)) return;

        String exactId = notification.exactNotificationId;
        if (exactId.startsWith(NotificationListener.NOTIFICATION_SCHEME)
                && NotificationListener.isNotificationActive(
                context, exactId, notification.postTime)
                && NotificationListener.openNotification(context, exactId)) {
            launchSucceeded = true;
            return;
        }
        Toast.makeText(context, "No exact notification destination is available.",
                Toast.LENGTH_SHORT).show();
    }

    private boolean handleExactNotificationResult(
            Context context, String appName, String packageName,
            SavedNotificationDestinationResolver.OpenResult result) {
        if (result.accepted()) {
            launchSucceeded = true;
            return true;
        }
        if (result == SavedNotificationDestinationResolver.OpenResult.APP_NOT_INSTALLED) {
            AppReinstallSupport.showUninstalledDialog(context, packageName, appName);
            return true;
        }
        if (result == SavedNotificationDestinationResolver.OpenResult.APP_DISABLED_CANNOT_ENABLE) {
            Toast.makeText(context, "The frozen app could not be re-enabled.",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
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
        return launchSucceeded;
    }

    @Override
    protected boolean didLaunchExternalActivity() {
        if (pojo instanceof NotificationPojo) return launchSucceeded;
        return super.didLaunchExternalActivity();
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
