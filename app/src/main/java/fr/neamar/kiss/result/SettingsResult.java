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
            return displayNotification(context, view, parent, (NotificationPojo) pojo);
        }

        if (view == null || view.findViewById(R.id.item_setting_prefix) == null) {
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

    private View displayNotification(Context context, View view, ViewGroup parent, NotificationPojo notification) {
        if (view == null || view.findViewById(R.id.item_notification_app) == null) {
            view = inflateFromId(context, R.layout.item_notification_timeline, parent);
        }

        TextView appName = view.findViewById(R.id.item_notification_app);
        TextView title = view.findViewById(R.id.item_notification_title);
        TextView text = view.findViewById(R.id.item_notification_text);
        Button dismiss = view.findViewById(R.id.item_notification_dismiss);
        ImageView icon = view.findViewById(R.id.item_notification_icon);

        appName.setText(notification.appName);
        title.setText(notification.getDisplayTitle());
        title.setVisibility(notification.getDisplayTitle().isEmpty() ? View.GONE : View.VISIBLE);
        text.setText(notification.getDisplayText());
        text.setVisibility(notification.getDisplayText().isEmpty() ? View.GONE : View.VISIBLE);

        if (!isHideIcons(context)) setAsyncDrawable(icon);
        else icon.setImageDrawable(null);

        dismiss.setEnabled(true);
        dismiss.setText(R.string.notification_mark_read);
        dismiss.setOnClickListener(v -> {
            if (NotificationListener.dismissNotification(context, notification.id)) {
                dismiss.setEnabled(false);
                dismiss.setText(R.string.notification_dismissed);
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
                return context.getPackageManager().getApplicationIcon(((NotificationPojo) pojo).notificationPackageName);
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
            launchNotification(context, (NotificationPojo) pojo);
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

    private void launchNotification(Context context, NotificationPojo notification) {
        if (NotificationListener.openNotification(context, notification.id)) {
            launchSucceeded = true;
            return;
        }

        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(notification.notificationPackageName);
        if (launchIntent == null) {
            Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launchIntent);
            launchSucceeded = true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Unable to open notification app", e);
            Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void hideFailedTarget(Context context) {
        if (pojo instanceof DisabledAppPojo || pojo instanceof NotificationPojo) return;
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
