package fr.neamar.kiss.result;

import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import fr.neamar.kiss.IconsHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.UIColors;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.ui.NotificationPopupDialog;
import fr.neamar.kiss.utils.AppIconMemoryCache;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public class AppResult extends ResultWithTags<AppPojo> {
    private static final String TAG = AppResult.class.getSimpleName();
    private static final ColorFilter FROZEN_ICON_FILTER;

    static {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        FROZEN_ICON_FILTER = new ColorMatrixColorFilter(matrix);
    }

    private volatile Drawable icon = null;
    private boolean launchSucceeded = true;

    AppResult(@NonNull AppPojo pojo) { super(pojo); }

    @NonNull
    @Override
    public View display(final Context context, View view, @NonNull ViewGroup parent, FuzzyScore fuzzyScore) {
        if (view == null) view = inflateFromId(context, R.layout.item_app, parent);

        boolean wasDisabled = pojo.isDisabled();
        boolean disabledNow = refreshLiveDisabledState(context);
        if (wasDisabled != disabledNow) clearIcon();

        TextView appName = view.findViewById(R.id.item_app_name);
        displayHighlighted(pojo.normalizedName, pojo.getName(), fuzzyScore, appName, context);

        TextView tagsView = view.findViewById(R.id.item_app_tag);
        displayTags(context, fuzzyScore, tagsView);

        ImageView appIcon = view.findViewById(R.id.item_app_icon);
        if (!isHideIcons(context)) {
            restoreWarmIcon(context);
            this.setAsyncDrawable(appIcon);
            applyFrozenIconFilter(appIcon);
        } else {
            appIcon.setImageDrawable(null);
            appIcon.clearColorFilter();
        }

        displayNotificationDot(context, view, false);
        displayNotificationMessage(context, view);
        return view;
    }

    @Override
    public void inflateFavorite(@NonNull Context context, @NonNull View favoriteView) {
        refreshLiveDisabledState(context);
        restoreWarmIcon(context);
        super.inflateFavorite(context, favoriteView);
        ImageView favoriteIcon = favoriteView.findViewById(R.id.favorite);
        applyFrozenIconFilter(favoriteIcon);
        displayNotificationDot(context, favoriteView, true);
    }

    /**
     * Frozen state is a property of the visible icon only. The cached Drawable remains full-color
     * so card/tile accent extraction and glass/3D backgrounds continue using the app's real colors.
     */
    private void applyFrozenIconFilter(@Nullable ImageView imageView) {
        if (imageView == null) return;
        if (pojo.isDisabled()) imageView.setColorFilter(FROZEN_ICON_FILTER);
        else imageView.clearColorFilter();
    }

    private void displayNotificationDot(Context context, View view, boolean isFavorite) {
        String packageKey = getPackageKey();
        ImageView notificationView = view.findViewById(R.id.item_notification_dot);
        if (notificationView == null) return;
        notificationView.setVisibility(
                NotificationListener.hasActiveNotificationGroup(context, packageKey)
                        ? View.VISIBLE : View.GONE);
        notificationView.setTag(packageKey);
        notificationView.setColorFilter(UIColors.getNotificationDotColor(context, isFavorite));
    }

    private void displayNotificationMessage(Context context, View view) {
        View row = view.findViewById(R.id.item_notification_row);
        TextView text = view.findViewById(R.id.item_notification_text);
        TextView appName = view.findViewById(R.id.item_app_name);
        View markRead = view.findViewById(R.id.item_notification_read);
        if (row == null || text == null || markRead == null) return;

        String packageKey = getPackageKey();
        String message = NotificationListener.getLatestMessage(context, packageKey);
        if (message == null || message.trim().isEmpty()) {
            row.setVisibility(View.GONE);
            row.setOnClickListener(null);
            text.setOnClickListener(null);
            if (appName != null) appName.setOnClickListener(null);
            markRead.setOnClickListener(null);
            return;
        }

        text.setText(message);
        row.setVisibility(View.VISIBLE);
        View.OnClickListener popupClick = v -> NotificationPopupDialog.showGroup(context, packageKey);
        row.setOnClickListener(popupClick);
        text.setOnClickListener(popupClick);
        if (appName != null) appName.setOnClickListener(popupClick);

        markRead.setOnClickListener(v -> {
            if (NotificationListener.markGroupRead(context, packageKey)) {
                row.setVisibility(View.GONE);
                ImageView dot = view.findViewById(R.id.item_notification_dot);
                if (dot != null) dot.setVisibility(View.GONE);
            } else {
                Toast.makeText(context, "Unable to mark notification as read", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String getPackageKey() { return pojo.getPackageKey(); }

    private boolean refreshLiveDisabledState(Context context) {
        // PackageManager can still see IceBox-disabled packages even when LauncherApps hides them.
        boolean enabled = AppLaunchUtils.isPackageEnabled(context, pojo.packageName);
        pojo.setDisabled(!enabled);
        return !enabled;
    }

    @Override
    protected void buildPopupMenu(Context context, ArrayAdapter<ListPopup.Item> adapter) {
        refreshLiveDisabledState(context);
        super.buildPopupMenu(context, adapter);
        adapter.add(new ListPopup.Item(context, R.string.menu_exclude));
        adapter.add(new ListPopup.Item(context, R.string.menu_app_rename));
        if (!pojo.isDisabled()) adapter.add(new ListPopup.Item(context, R.string.menu_app_details));
        adapter.add(new ListPopup.Item(context, R.string.menu_app_store));

        boolean uninstallDisabled = pojo.isDisabled();
        if (!uninstallDisabled) {
            ApplicationInfo ai = PackageManagerUtils.getApplicationInfo(context, pojo.packageName, pojo.userHandle);
            uninstallDisabled = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        }
        if (!uninstallDisabled) {
            UserManager userManager = ContextCompat.getSystemService(context, UserManager.class);
            if (userManager != null) {
                Bundle restrictions = userManager.getUserRestrictions(pojo.userHandle.getRealHandle());
                uninstallDisabled = restrictions.getBoolean(UserManager.DISALLOW_APPS_CONTROL, false)
                        || restrictions.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS, false);
            }
        }
        if (!uninstallDisabled) adapter.add(new ListPopup.Item(context, R.string.menu_app_uninstall));
        if (KissApplication.getApplication(context).getRootHandler().isRootActivated()
                && KissApplication.getApplication(context).getRootHandler().isRootAvailable()) {
            adapter.add(new ListPopup.Item(context, R.string.menu_app_hibernate));
        }
    }

    @Override
    protected boolean popupMenuClickHandler(final Context context, final RecordAdapter parent, int stringId, View parentView) {
        if (stringId == R.string.menu_app_details) { launchAppDetails(context); return true; }
        if (stringId == R.string.menu_app_store) { launchAppStore(context); return true; }
        if (stringId == R.string.menu_app_uninstall) { launchUninstall(context); return true; }
        if (stringId == R.string.menu_app_hibernate) { hibernate(context); return true; }
        if (stringId == R.string.menu_exclude) {
            final int EXCLUDE_HISTORY_ID = 0;
            final int EXCLUDE_KISS_ID = 1;
            PopupMenu popupExcludeMenu = new PopupMenu(context, parentView);
            popupExcludeMenu.getMenu().add(EXCLUDE_HISTORY_ID, Menu.NONE, Menu.NONE, R.string.menu_exclude_history);
            popupExcludeMenu.getMenu().add(EXCLUDE_KISS_ID, Menu.NONE, Menu.NONE, R.string.menu_exclude_kiss);
            popupExcludeMenu.setOnMenuItemClickListener(item -> {
                if (item.getGroupId() == EXCLUDE_HISTORY_ID) { excludeFromHistory(context, pojo); return true; }
                if (item.getGroupId() == EXCLUDE_KISS_ID) { excludeFromKiss(context, pojo, parent); return true; }
                return true;
            });
            popupExcludeMenu.show();
            return true;
        }
        if (stringId == R.string.menu_app_rename) { launchRenameDialog(context, parent, pojo); return true; }
        return super.popupMenuClickHandler(context, parent, stringId, parentView);
    }

    private void excludeFromHistory(Context context, AppPojo app) {
        KissApplication.getApplication(context).getDataHandler().addToExcludedFromHistory(app);
        removeFromHistory(context);
        Toast.makeText(context, R.string.excluded_app_history_added, Toast.LENGTH_LONG).show();
    }

    private void excludeFromKiss(Context context, AppPojo app, final RecordAdapter parent) {
        parent.removeResult(AppResult.this);
        KissApplication.getApplication(context).getDataHandler().addToExcluded(app);
        Toast.makeText(context, R.string.excluded_app_list_added, Toast.LENGTH_LONG).show();
    }

    private void launchRenameDialog(final Context context, RecordAdapter parent, final AppPojo app) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(context.getResources().getString(R.string.app_rename_title));
        builder.setView(R.layout.rename_dialog);
        builder.setPositiveButton(R.string.custom_name_rename, (dialog, which) -> {
            EditText input = ((AlertDialog) dialog).findViewById(R.id.rename);
            dialog.dismiss();
            String newName = input.getText().toString().trim();
            app.setName(newName);
            KissApplication.getApplication(context).getDataHandler().renameApp(app.getComponentName(), newName);
            Toast.makeText(context, context.getResources().getString(R.string.app_rename_confirmation, app.getName()), Toast.LENGTH_SHORT).show();
            setTranscriptModeAlwaysScroll(parent);
        });
        builder.setNegativeButton(R.string.custom_name_set_default, (dialog, which) -> {
            dialog.dismiss();
            KissApplication.getApplication(context).getDataHandler().removeRenameApp(getComponentName());
            String name = PackageManagerUtils.getLabel(context, new ComponentName(app.packageName, app.activityName), app.userHandle);
            if (name != null) {
                app.setName(name);
                Toast.makeText(context, context.getResources().getString(R.string.app_rename_confirmation, app.getName()), Toast.LENGTH_SHORT).show();
            }
            setTranscriptModeAlwaysScroll(parent);
        });
        builder.setNeutralButton(android.R.string.cancel, (dialog, which) -> { dialog.cancel(); setTranscriptModeAlwaysScroll(parent); });
        setTranscriptModeDisabled(parent);
        AlertDialog dialog = builder.create();
        dialog.show();
        ((TextView) dialog.findViewById(R.id.rename)).setText(app.getName());
    }

    private void launchAppDetails(Context context) {
        LauncherApps launcher = ContextCompat.getSystemService(context, LauncherApps.class);
        if (launcher != null) launcher.startAppDetailsActivity(getClassName(), pojo.userHandle.getRealHandle(), null, null);
    }

    private void launchAppStore(Context context) {
        try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pojo.packageName))); }
        catch (ActivityNotFoundException e) { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pojo.packageName))); }
    }

    private void hibernate(Context context) {
        String msg = context.getResources().getString(R.string.toast_hibernate_completed);
        if (!KissApplication.getApplication(context).getRootHandler().hibernateApp(pojo.packageName)) msg = context.getResources().getString(R.string.toast_hibernate_error);
        else KissApplication.getApplication(context).getDataHandler().reloadApps();
        Toast.makeText(context, String.format(msg, pojo.getName()), Toast.LENGTH_SHORT).show();
    }

    private void launchUninstall(Context context) {
        Intent intent = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pojo.packageName, null));
        intent.putExtra(Intent.EXTRA_USER, pojo.userHandle.getRealHandle());
        context.startActivity(intent);
    }

    private void restoreWarmIcon(Context context) {
        if (icon != null) return;
        icon = AppIconMemoryCache.get(context, pojo.getComponentName());
    }

    @Override boolean isDrawableCached() { return icon != null; }

    @Override
    void setDrawableCache(Drawable drawable) {
        icon = drawable;
        if (drawable == null) AppIconMemoryCache.invalidate(pojo.getComponentName());
    }

    @Override
    public Drawable getDrawable(Context context) {
        refreshLiveDisabledState(context);
        if (icon == null) {
            synchronized (this) {
                if (icon == null) {
                    icon = AppIconMemoryCache.get(context, pojo.getComponentName());
                }
                if (icon == null) {
                    IconsHandler iconsHandler = KissApplication.getApplication(context).getIconsHandler();
                    icon = iconsHandler.getDrawableIconForPackage(getClassName(), pojo.userHandle);
                    AppIconMemoryCache.put(context, pojo.getComponentName(), icon);
                }
            }
        }
        // Never desaturate the cached drawable itself. Tile/card accent extraction must continue
        // seeing the real app colors; only the ImageView is greyed by applyFrozenIconFilter().
        return icon;
    }

    private void clearVisibleDisabledFilter(@Nullable View parentView) {
        if (parentView == null) return;
        View candidate = parentView.findViewById(R.id.item_app_icon);
        if (candidate == null) candidate = parentView.findViewById(R.id.favorite);
        if (candidate instanceof ImageView) {
            ImageView imageView = (ImageView) candidate;
            imageView.clearColorFilter();
            imageView.invalidate();
        }
    }

    @Override
    public void doLaunch(Context context, View v) {
        launchSucceeded = false;
        boolean wasFrozen = refreshLiveDisabledState(context);
        if (wasFrozen) {
            if (!pojo.userHandle.isCurrentUser()) {
                Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
                return;
            }
            if (!AppLaunchUtils.ensurePackageEnabled(context, pojo.packageName)) {
                pojo.setDisabled(true);
                applyFrozenIconFilter(findVisibleIcon(v));
                Toast.makeText(context, "Unable to enable " + pojo.getName(), Toast.LENGTH_LONG).show();
                return;
            }
            pojo.setDisabled(false);
            clearVisibleDisabledFilter(v);
            clearIcon();
        }

        try {
            LauncherApps launcher = ContextCompat.getSystemService(context, LauncherApps.class);
            if (launcher == null) throw new ActivityNotFoundException();
            Rect sourceBounds = null;
            Bundle opts = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && v != null) {
                View potentialIcon = v.findViewById(R.id.item_app_icon);
                if (potentialIcon == null) potentialIcon = v.findViewById(R.id.favorite);
                if (potentialIcon != null) {
                    sourceBounds = getViewBounds(potentialIcon);
                    opts = ActivityOptions.makeClipRevealAnimation(potentialIcon, 0, 0,
                            potentialIcon.getMeasuredWidth(), potentialIcon.getMeasuredHeight()).toBundle();
                }
            }
            launcher.startMainActivity(getClassName(), pojo.userHandle.getRealHandle(), sourceBounds, opts);
            markLaunchSucceeded(wasFrozen, v);
        } catch (ActivityNotFoundException | NullPointerException | SecurityException e) {
            Log.w(TAG, "Unable to launch activity", e);

            // Immediately after IceBox/root unfreezes a package, LauncherApps can lag behind the
            // PackageManager state. Try the package launch intent before treating the app as broken.
            if (wasFrozen && AppLaunchUtils.launchPackage(context, pojo.packageName)) {
                markLaunchSucceeded(true, v);
                return;
            }

            // Never hide/exclude an app merely because IceBox froze it between index and tap.
            if (!AppLaunchUtils.isPackageEnabled(context, pojo.packageName)) {
                pojo.setDisabled(true);
                applyFrozenIconFilter(findVisibleIcon(v));
                clearIcon();
                KissApplication.getApplication(context).getDataHandler().reloadApps();
            } else {
                // Only a genuinely enabled, broken launcher component is treated as a failed app target.
                KissApplication.getApplication(context).getDataHandler().addToExcluded(pojo);
                removeFromHistory(context);
            }
            Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
        }
    }

    private void markLaunchSucceeded(boolean wasFrozen, @Nullable View parentView) {
        launchSucceeded = true;
        pojo.setDisabled(false);
        if (wasFrozen) {
            clearVisibleDisabledFilter(parentView);
            clearIcon();
        }
    }

    @Nullable
    private ImageView findVisibleIcon(@Nullable View parentView) {
        if (parentView == null) return null;
        View candidate = parentView.findViewById(R.id.item_app_icon);
        if (candidate == null) candidate = parentView.findViewById(R.id.favorite);
        return candidate instanceof ImageView ? (ImageView) candidate : null;
    }

    @Override
    protected boolean canAddToHistory() { return launchSucceeded && !pojo.isDisabled(); }

    @Nullable
    @Override
    protected Rect getViewBounds(@Nullable View view) {
        if (view == null) return null;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    public String getComponentName() { return pojo.getComponentName(); }
    public ComponentName getClassName() { return pojo.getComponent(); }
    @Override protected boolean isAllowedAsFavorite() { return true; }
    @Override protected boolean canRemoveFromHistory(Context context) { return true; }
    @Override protected boolean canHaveCustomIcon(Context context, IconPack iconPack) { return true; }
}
