package fr.neamar.kiss.result;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.net.URISyntaxException;
import java.util.List;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.IconsHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.ui.NotificationPopupDialog;
import fr.neamar.kiss.utils.DrawableUtils;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.UserHandle;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public class ShortcutsResult extends ResultWithTags<ShortcutPojo> {

    private static final String TAG = ShortcutsResult.class.getSimpleName();
    private static final String VERTICAL_CARDS = "vertical_cards";

    private volatile Drawable icon = null;
    private volatile Drawable appDrawable = null;
    private boolean launchSucceeded;

    ShortcutsResult(@NonNull ShortcutPojo pojo) {
        super(pojo);
    }

    @NonNull
    @Override
    public View display(final Context context, View view, @NonNull ViewGroup parent, FuzzyScore fuzzyScore) {
        if (view == null)
            view = inflateFromId(context, R.layout.item_shortcut, parent);

        TextView shortcutName = view.findViewById(R.id.item_app_name);

        displayHighlighted(pojo.normalizedName, pojo.getName(), fuzzyScore, shortcutName, context);

        TextView tagsView = view.findViewById(R.id.item_shortcut_tag);
        displayTags(context, fuzzyScore, tagsView);

        final ImageView shortcutIcon = view.findViewById(R.id.item_shortcut_icon);
        final ImageView appIcon = view.findViewById(R.id.item_app_icon);

        if (!isHideIcons(context)) {
            // set shortcut icon
            this.setAsyncDrawable(shortcutIcon);

            // Prepare
            if (isSubIconVisible(context)) {
                appIcon.setVisibility(View.VISIBLE);
                setAsyncDrawable(appIcon, android.R.color.transparent, false, () -> appDrawable != null, this::getAppDrawable, (drawable) -> appDrawable = drawable);
            } else {
                appIcon.setVisibility(View.GONE);
            }
        } else {
            appIcon.setImageDrawable(null);
            shortcutIcon.setImageDrawable(null);
        }

        displaySmartCardTargetNotification(context, view);
        return view;
    }

    /**
     * Vertical cards should represent the app behind an Ice Box shortcut, not Ice Box itself.
     * This method is deliberately card-only so existing shortcut rendering in all other layouts
     * remains unchanged.
     */
    private void displaySmartCardTargetNotification(Context context, View view) {
        View row = view.findViewById(R.id.item_notification_row);
        TextView text = view.findViewById(R.id.item_notification_text);
        View markRead = view.findViewById(R.id.item_notification_read);
        if (row == null || text == null || markRead == null) return;

        String layout = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("smart-history-layout", "vertical");
        if (!VERTICAL_CARDS.equals(layout)) {
            row.setVisibility(View.GONE);
            row.setOnClickListener(null);
            text.setOnClickListener(null);
            markRead.setOnClickListener(null);
            return;
        }

        String targetPackage = resolveTargetPackageName(context);
        if (TextUtils.isEmpty(targetPackage)) {
            row.setVisibility(View.GONE);
            return;
        }

        String groupKey = pojo.getUserHandle().getRealHandle().hashCode() + "|" + targetPackage;
        String activeMessage = NotificationListener.getLatestMessage(context, groupKey);
        List<NotificationHistoryRecord> history = SmartStateStore.queryNotifications(
                context, targetPackage, null, 1);
        String latestMessage = activeMessage == null ? "" : activeMessage.trim();
        if (latestMessage.isEmpty() && !history.isEmpty()) {
            NotificationHistoryRecord latest = history.get(0);
            latestMessage = combineNotification(latest.title, latest.text);
        }

        if (latestMessage.isEmpty()) {
            row.setVisibility(View.GONE);
            row.setOnClickListener(null);
            text.setOnClickListener(null);
            markRead.setOnClickListener(null);
            return;
        }

        text.setText(latestMessage);
        row.setVisibility(View.VISIBLE);

        List<NotificationListener.NotificationSnapshot> active =
                NotificationListener.getGroupNotifications(context, groupKey);
        if (!active.isEmpty()) {
            View.OnClickListener popupClick = v -> NotificationPopupDialog.showGroup(context, groupKey);
            row.setOnClickListener(popupClick);
            text.setOnClickListener(popupClick);
            markRead.setVisibility(View.VISIBLE);
            markRead.setOnClickListener(v -> {
                if (NotificationListener.markGroupRead(context, groupKey)) {
                    row.setVisibility(View.GONE);
                } else {
                    Toast.makeText(context, "Unable to mark notification as read", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            // Historical message only: keep it visible on the card, but there is no active
            // notification action to invoke or dismiss.
            row.setOnClickListener(null);
            text.setOnClickListener(null);
            markRead.setOnClickListener(null);
            markRead.setVisibility(View.GONE);
        }
    }

    private String combineNotification(String title, String body) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanBody = body == null ? "" : body.trim();
        if (cleanTitle.isEmpty()) return cleanBody;
        if (cleanBody.isEmpty() || cleanTitle.equals(cleanBody)) return cleanTitle;
        return cleanTitle + ": " + cleanBody;
    }

    /** Resolve the real app behind a shortcut when possible. */
    @Nullable
    public String resolveTargetPackageName(Context context) {
        // First prefer the actual component encoded by the shortcut intent/activity.
        if (pojo.isOreoShortcut()) {
            ShortcutInfo shortcutInfo = getShortCut(context);
            if (shortcutInfo != null && shortcutInfo.getActivity() != null) {
                String packageName = shortcutInfo.getActivity().getPackageName();
                if (!TextUtils.isEmpty(packageName) && !packageName.equals(pojo.packageName)) {
                    return packageName;
                }
            }
        } else {
            try {
                Intent intent = Intent.parseUri(pojo.intentUri, 0);
                ComponentName componentName = PackageManagerUtils.getComponentName(context, intent);
                if (componentName != null) {
                    String packageName = componentName.getPackageName();
                    if (!TextUtils.isEmpty(packageName) && !packageName.equals(pojo.packageName)) {
                        return packageName;
                    }
                }
            } catch (URISyntaxException | RuntimeException e) {
                Log.w(TAG, "Unable to resolve shortcut target package for " + pojo.getName(), e);
            }
        }

        // Ice Box shortcuts may intentionally route through Ice Box itself. In that case use the
        // visible target app label to match the app that actually owns the notifications.
        String targetLabel = cleanIceBoxLabel(pojo.getName());
        if (!targetLabel.equals(pojo.getName())) {
            for (String[] entry : SmartStateStore.getNotificationApps(context)) {
                if (entry == null || entry.length < 2) continue;
                String packageName = entry[0];
                String appName = entry[1];
                if (!TextUtils.isEmpty(packageName)
                        && appName != null
                        && targetLabel.equalsIgnoreCase(appName.trim())) {
                    return packageName;
                }
            }
        }
        return null;
    }

    private String cleanIceBoxLabel(String label) {
        if (label == null) return "";
        String value = label.trim();
        if (!value.regionMatches(true, 0, "Ice Box:", 0, "Ice Box:".length())) return value;
        value = value.substring("Ice Box:".length()).trim();
        while (value.startsWith("❄") || value.startsWith("️")) {
            value = value.substring(1).trim();
        }
        return value;
    }

    private Drawable getAppDrawable(Context context) {
        if (appDrawable == null) {
            synchronized (this) {
                if (appDrawable == null) {
                    IconsHandler iconsHandler = KissApplication.getApplication(context).getIconsHandler();

                    if (pojo.isOreoShortcut()) {
                        // Retrieve activity icon from oreo shortcut
                        appDrawable = getDrawableFromOreoShortcut(context);
                    }

                    if (appDrawable == null) {
                        // Retrieve activity icon by intent URI
                        try {
                            Intent intent = Intent.parseUri(pojo.intentUri, 0);
                            ComponentName componentName = PackageManagerUtils.getComponentName(context, intent);
                            if (componentName != null) {
                                UserHandle userHandle = pojo.getUserHandle();
                                appDrawable = iconsHandler.getDrawableIconForPackage(PackageManagerUtils.getLaunchingComponent(context, componentName, userHandle), userHandle);
                            }
                        } catch (NullPointerException e) {
                            Log.e(TAG, "Unable to get activity icon for '" + pojo.getName() + "'", e);
                        } catch (URISyntaxException e) {
                            Log.e(TAG, "Unable to parse uri for '" + pojo.getName() + "'", e);
                        }
                    }

                    if (appDrawable == null) {
                        // Retrieve app icon (no Oreo shortcut or a shortcut from an activity that was removed from an installed app)
                        appDrawable = PackageManagerUtils.getApplicationIcon(context, pojo.packageName);
                        if (appDrawable != null) {
                            appDrawable = iconsHandler.applyIconMask(context, appDrawable);
                        }
                    }
                }
            }
        }
        DrawableUtils.setDisabled(appDrawable, this.pojo.isDisabled());
        return appDrawable;
    }

    @Override
    boolean isDrawableCached() {
        return icon != null;
    }

    @Override
    void setDrawableCache(Drawable drawable) {
        icon = drawable;
    }

    public Drawable getDrawable(Context context) {
        if (icon == null) {
            synchronized (this) {
                if (icon == null) {
                    IconsHandler iconsHandler = KissApplication.getApplication(context).getIconsHandler();
                    icon = iconsHandler.getDrawableIconForShortcut(this.pojo, getShortCut(context));
                }
            }
        }
        DrawableUtils.setDisabled(icon, this.pojo.isDisabled());
        return icon;
    }

    @Override
    protected void doLaunch(Context context, View v) {
        launchSucceeded = false;
        if (pojo.isOreoShortcut()) {
            // Oreo shortcuts
            doOreoLaunch(context, v);
        } else {
            // Pre-oreo shortcuts
            try {
                Intent intent = Intent.parseUri(pojo.intentUri, 0);
                setSourceBounds(intent, v);
                context.startActivity(intent);
                launchSucceeded = true;
            } catch (Exception e) {
                // Application was just removed?
                Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void doOreoLaunch(Context context, View v) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            final LauncherApps launcherApps = ContextCompat.getSystemService(context, LauncherApps.class);
            assert launcherApps != null;

            // Only the default launcher is allowed to start shortcuts
            if (!launcherApps.hasShortcutHostPermission()) {
                Toast.makeText(context, context.getString(R.string.shortcuts_no_host_permission), Toast.LENGTH_LONG).show();
                return;
            }

            ShortcutInfo shortcutInfo = getShortCut(context);
            if (shortcutInfo != null) {
                try {
                    launcherApps.startShortcut(shortcutInfo, v.getClipBounds(), null);
                    launchSucceeded = true;
                    return;
                } catch (ActivityNotFoundException | IllegalStateException e) {
                    Log.w(TAG, "Unable to launch shortcut " + pojo.getName(), e);
                }
            }
        }

        // Application removed? Invalid shortcut? Shortcut to an app on an unmounted SD card?
        Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
    }

    @Override
    protected boolean canAddToHistory() {
        return launchSucceeded && !pojo.isDisabled();
    }

    @Nullable
    private ShortcutInfo getShortCut(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ShortcutUtil.getShortCut(context, pojo.getUserHandle().getRealHandle(), pojo.packageName, pojo.getOreoId());
        } else {
            return null;
        }
    }

    private Drawable getDrawableFromOreoShortcut(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ShortcutInfo shortcutInfo = getShortCut(context);
            if (shortcutInfo != null && shortcutInfo.getActivity() != null) {
                UserHandle user = new UserHandle(context, shortcutInfo.getUserHandle());
                IconsHandler iconsHandler = KissApplication.getApplication(context).getIconsHandler();
                return iconsHandler.getDrawableIconForPackage(shortcutInfo.getActivity(), user);
            }
        }
        return null;
    }

    @Override
    protected void buildPopupMenu(Context context, ArrayAdapter<ListPopup.Item> adapter) {
        super.buildPopupMenu(context, adapter);

        if (!this.pojo.isPinned() && this.pojo.isOreoShortcut() && !PackageManagerUtils.isPrivateProfile(context, this.pojo.getUserHandle())) {
            adapter.add(new ListPopup.Item(context, R.string.menu_shortcut_pin));
        }
        if (this.pojo.isPinned() && !PackageManagerUtils.isPrivateProfile(context, this.pojo.getUserHandle())) {
            adapter.add(new ListPopup.Item(context, R.string.menu_shortcut_remove));
        }
    }

    @Override
    boolean popupMenuClickHandler(Context context, RecordAdapter parent, int stringId, View parentView) {
        if (stringId == R.string.menu_shortcut_pin) {
            pinShortcut(context, pojo);
            return true;
        } else if (stringId == R.string.menu_shortcut_remove) {
            launchUninstall(context, pojo);
            // Also remove item, since it will be uninstalled
            parent.removeResult(this);
            return true;
        }
        return super.popupMenuClickHandler(context, parent, stringId, parentView);
    }

    private void launchUninstall(Context context, ShortcutPojo pojo) {
        DataHandler dh = KissApplication.getApplication(context).getDataHandler();
        dh.unpinShortcut(pojo);
    }

    private void pinShortcut(Context context, ShortcutPojo pojo) {
        DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
        dataHandler.pinShortcut(pojo);
    }

    /**
     * @return true, if shortcut will not be changed by providing app anymore
     */
    @Override
    protected boolean isAllowedAsFavorite() {
        return !this.pojo.isDynamic() || this.pojo.isPinned();
    }

    @Override
    protected boolean canRemoveFromHistory(Context context) {
        return true;
    }

    /**
     * @return true, if shortcut will not be changed by providing app anymore
     */
    @Override
    protected boolean canHaveCustomIcon(Context context, IconPack iconPack) {
        return isAllowedAsFavorite();
    }
}
