package fr.neamar.kiss.result;

import android.app.PendingIntent;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.net.URISyntaxException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.IconsHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.AppCatalogRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.notification.NotificationAvatarSupport;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.ui.TileLaunchCounter;
import fr.neamar.kiss.utils.AppIdentityResolver;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.DrawableUtils;
import fr.neamar.kiss.utils.FrozenAppPreferences;
import fr.neamar.kiss.utils.IceBoxFallbackTargetPolicy;
import fr.neamar.kiss.utils.IceBoxSdkBridge;
import fr.neamar.kiss.utils.IceBoxLaunchBridge;
import fr.neamar.kiss.utils.IceBoxShortcutIdentity;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.SavedNotificationDestinationResolver;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.UserHandle;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;
import fr.neamar.kiss.shortcut.SavedShortcutRoutes;

public class ShortcutsResult extends ResultWithTags<ShortcutPojo> {

    private static final String TAG = ShortcutsResult.class.getSimpleName();
    private static final String VERTICAL_CARDS = "vertical_cards";
    // Retry only after a user-initiated enable; never poll in the background.

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
            this.setAsyncDrawable(shortcutIcon);
            if (isSubIconVisible(context)) {
                appIcon.setVisibility(View.VISIBLE);
                setAsyncDrawable(appIcon, android.R.color.transparent, false, () -> appDrawable != null,
                        this::getAppDrawable, (drawable) -> appDrawable = drawable);
            } else {
                appIcon.setVisibility(View.GONE);
            }
        } else {
            appIcon.setImageDrawable(null);
            shortcutIcon.setImageDrawable(null);
        }

        if (!isNativeListScrolling(parent)) {
            displaySmartCardTargetNotification(context, view);
        }
        return view;
    }

    private void displaySmartCardTargetNotification(Context context, View view) {
        View row = view.findViewById(R.id.item_notification_row);
        ImageView avatar = view.findViewById(R.id.item_notification_avatar);
        TextView text = view.findViewById(R.id.item_notification_text);
        View markRead = view.findViewById(R.id.item_notification_read);
        if (row == null || avatar == null || text == null || markRead == null) return;
        avatar.setImageDrawable(null);
        avatar.setVisibility(View.GONE);

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
        List<NotificationListener.NotificationSnapshot> active =
                NotificationListener.getGroupNotifications(context, groupKey);
        NotificationListener.NotificationSnapshot latestActive =
                active.isEmpty() ? null : active.get(0);
        String activeMessage = latestActive == null ? ""
                : combineNotification(latestActive.title, latestActive.text);
        NotificationHistoryRecord latestSaved =
                fr.neamar.kiss.notification.NotificationWorkPolicy.historyEnabled(context)
                        ? SmartStateStore.latestNotificationForPackage(context, targetPackage) : null;
        String latestMessage = activeMessage.trim();
        if (latestMessage.isEmpty() && latestSaved != null) {
            latestMessage = combineNotification(latestSaved.title, latestSaved.text);
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

        String avatarId = !active.isEmpty() ? active.get(0).id
                : latestSaved == null ? null : latestSaved.notificationId;
        Drawable notificationAvatar = NotificationAvatarSupport.avatar(
                context, avatarId, targetPackage,
                latestSaved == null ? null : latestSaved.title,
                latestSaved == null ? null : latestSaved.shortcutId);
        if (notificationAvatar != null) {
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setImageDrawable(notificationAvatar);
            avatar.setVisibility(View.VISIBLE);
        }
        if (!active.isEmpty()) {
            View.OnClickListener exactNotificationClick = v -> {
                TileLaunchCounter.recordNotification(
                        context, latestActive.id, latestActive.postTime);
                if (!NotificationListener.openNotification(
                        context, latestActive.id, latestActive.postTime)
                        && !AppLaunchUtils.launchPackage(context, targetPackage)) {
                    Toast.makeText(context,
                            "No exact notification destination is available and the app could not be opened.",
                            Toast.LENGTH_SHORT).show();
                }
            };
            row.setOnClickListener(exactNotificationClick);
            text.setOnClickListener(exactNotificationClick);
            markRead.setVisibility(View.VISIBLE);
            markRead.setOnClickListener(v -> {
                if (NotificationListener.markGroupRead(context, groupKey)) {
                    row.setVisibility(View.GONE);
                } else {
                    Toast.makeText(context, "Unable to mark notification as read", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            if (latestSaved != null) {
                View.OnClickListener exactSavedNotificationClick = v -> {
                    TileLaunchCounter.recordNotification(
                            context, latestSaved.notificationId, latestSaved.postTime);
                    SavedNotificationDestinationResolver.OpenResult openResult =
                            SavedNotificationDestinationResolver.openExactOrAppResult(
                                    context, latestSaved);
                    if (!openResult.accepted()) {
                        Toast.makeText(context,
                                "Unable to open this notification or its app.",
                                Toast.LENGTH_SHORT).show();
                    }
                };
                row.setOnClickListener(exactSavedNotificationClick);
                text.setOnClickListener(exactSavedNotificationClick);
            } else {
                row.setOnClickListener(null);
                text.setOnClickListener(null);
            }
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

    @Nullable
    public String resolveTargetPackageName(Context context) {
        DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
        String canonicalPackage = AppIdentityResolver.canonicalPackage(context, dataHandler, pojo);
        if (!TextUtils.isEmpty(canonicalPackage)) return canonicalPackage;
        if (!TextUtils.isEmpty(pojo.targetPackage)) return pojo.targetPackage;

        if (pojo.isOreoShortcut() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ShortcutInfo shortcutInfo = getShortCut(context);
            if (shortcutInfo != null && shortcutInfo.getActivity() != null
                    && !ShortcutUtil.isIceBoxPublisher(context, pojo.packageName)) {
                String packageName = shortcutInfo.getActivity().getPackageName();
                if (!TextUtils.isEmpty(packageName)) return packageName;
            }
        } else if (!pojo.isOreoShortcut()) {
            try {
                Intent intent = Intent.parseUri(pojo.intentUri, 0);
                ComponentName componentName = PackageManagerUtils.getComponentName(context, intent);
                if (componentName != null) {
                    String packageName = componentName.getPackageName();
                    if (!TextUtils.isEmpty(packageName)) return packageName;
                }
            } catch (URISyntaxException | RuntimeException e) {
                Log.w(TAG, "Unable to resolve shortcut target package for " + pojo.getName(), e);
            }
        }

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
                        appDrawable = getDrawableFromOreoShortcut(context);
                    }
                    if (appDrawable == null) {
                        try {
                            Intent intent = Intent.parseUri(pojo.intentUri, 0);
                            ComponentName componentName = PackageManagerUtils.getComponentName(context, intent);
                            if (componentName != null) {
                                UserHandle userHandle = pojo.getUserHandle();
                                appDrawable = iconsHandler.getDrawableIconForPackage(
                                        PackageManagerUtils.getLaunchingComponent(context, componentName, userHandle),
                                        userHandle);
                            }
                        } catch (NullPointerException e) {
                            Log.e(TAG, "Unable to get activity icon for '" + pojo.getName() + "'", e);
                        } catch (URISyntaxException e) {
                            Log.e(TAG, "Unable to parse uri for '" + pojo.getName() + "'", e);
                        }
                    }
                    if (appDrawable == null) {
                        appDrawable = PackageManagerUtils.getApplicationIcon(context, pojo.packageName);
                        if (appDrawable != null) appDrawable = iconsHandler.applyIconMask(context, appDrawable);
                    }
                }
            }
        }
        DrawableUtils.setDisabled(appDrawable, shouldGreyShortcut(context));
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
                    KissApplication application = KissApplication.getApplication(context);
                    IconsHandler iconsHandler = application.getIconsHandler();

                    // IceBox is a launch route, not the visual identity of the frozen app. Resolve
                    // the canonical app package first (including old shortcuts whose targetPackage
                    // was not persisted yet), then use the same icon-pack pipeline as a normal app.
                    if (ShortcutUtil.isIceBoxPublisher(context, pojo.packageName)) {
                        String targetPackage = AppIdentityResolver.canonicalPackage(
                                context, application.getDataHandler(), pojo);
                        if (!TextUtils.isEmpty(targetPackage)
                                && !TextUtils.equals(targetPackage, pojo.packageName)) {
                            icon = iconsHandler.getDrawableIconForPackageName(
                                    targetPackage, pojo.getUserHandle());
                        }
                    }

                    if (icon == null) {
                        icon = iconsHandler.getDrawableIconForShortcut(this.pojo, getShortCut(context));
                    }
                }
            }
        }
        DrawableUtils.setDisabled(icon, shouldGreyShortcut(context));
        return icon;
    }

    private boolean shouldGreyShortcut(@NonNull Context context) {
        if (ShortcutUtil.isIceBoxPublisher(context, pojo.packageName)) return false;
        return pojo.isDisabled() && FrozenAppPreferences.grey(context);
    }

    private static final long[] ENABLED_SHORTCUT_RETRY_DELAYS_MS =
            {350L, 700L, 1_200L, 2_000L, 3_000L};
    private boolean launchPending;

    @Override
    protected void doLaunch(Context context, View v) {
        if (launchPending) return; // One tap produces one enable-and-launch operation.
        launchSucceeded = false;
        if (pojo.isOreoShortcut()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                launchPending = true;
                if (SavedShortcutRoutes.preserveBeforeLaunch(context, pojo, () -> {
                    launchPending = false;
                    doOreoLaunch(context, v, true);
                })) return;
                launchPending = false;
            }
            doOreoLaunch(context, v, false);
        } else {
            doLegacyShortcutLaunch(context, v);
        }
    }

    /** A user-created shortcut must keep its original Intent, not open an unrelated app. */
    private void doLegacyShortcutLaunch(Context context, View view) {
        final Intent intent;
        try {
            intent = Intent.parseUri(pojo.intentUri, 0);
        } catch (URISyntaxException | RuntimeException e) {
            Log.w(TAG, "Invalid saved shortcut Intent: " + pojo.id, e);
            if (!openKnownIceBoxTarget(context, false)) notifyExactShortcutUnavailable(context);
            return;
        }

        String appPackage = intent.getPackage();
        if (TextUtils.isEmpty(appPackage) && intent.getComponent() != null) {
            appPackage = intent.getComponent().getPackageName();
        }
        if (TextUtils.isEmpty(appPackage) && !TextUtils.isEmpty(pojo.targetPackage)) {
            appPackage = pojo.targetPackage;
        }
        final String pkg = appPackage;
        ComponentName component = intent.getComponent();
        if (component != null && !TextUtils.isEmpty(pkg)
                && !TextUtils.equals(pkg, component.getPackageName())) {
            Log.w(TAG, "Shortcut has mismatched owner and Activity: " + pojo.id);
            notifyExactShortcutUnavailable(context);
            return;
        }
        boolean frozen = !TextUtils.isEmpty(pkg)
                && (!AppLaunchUtils.isPackageEnabledNow(context, pkg)
                    || (component != null && !AppLaunchUtils.isActivityEnabledNow(context, component)));

        if (!frozen) {
            openLegacyIntent(context, view, intent, false, null);
            return;
        }
        if (!FrozenAppPreferences.autoEnable(context)) {
            showAutoEnableDisabled(context);
            return;
        }
        launchPending = true;
        AppLaunchUtils.ensurePackageEnabledAsync(context, pkg, component, true, enabled -> {
            if (!enabled) {
                launchPending = false;
                // A legacy Ice Box wrapper's removed Activity belongs to Ice Box, not
                // the frozen target app. Recover its saved target before handing off an Intent.
                if (openKnownIceBoxTarget(context, true)) return;
                if (IceBoxLaunchBridge.open(context, pkg,
                        android.os.Process.myUserHandle().hashCode(), getViewBounds(view), intent)) {
                    onExactLaunchSucceeded(context, true);
                } else {
                    showUnableToEnable(context, pkg);
                }
                return;
            }
            openLegacyIntent(context, view, intent, true, pkg);
        });
    }

    private void openLegacyIntent(Context context, View view, Intent intent,
                                  boolean asynchronous, @Nullable String enabledPackage) {
        launchPending = false;
        try {
            setSourceBounds(intent, view);
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            onExactLaunchSucceeded(context, asynchronous);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to open saved shortcut " + pojo.id, e);
            if (openKnownIceBoxTarget(context, asynchronous)) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                launchPending = true;
                if (SavedShortcutRoutes.openLegacy(context, pojo, intent, opened -> {
                    launchPending = false;
                    if (opened) onExactLaunchSucceeded(context, true);
                    else notifyExactShortcutUnavailable(context);
                })) return;
                launchPending = false;
            }
            notifyExactShortcutUnavailable(context);
        } finally {
            if (enabledPackage != null) {
                AppLaunchUtils.refreshLauncherAfterEnable(context, enabledPackage);
            }
        }
    }

    /** Older IceBox releases also created legacy Intent shortcuts, not just Oreo IDs. */
    private boolean openKnownIceBoxTarget(Context context, boolean asynchronous) {
        if (!pojo.getUserHandle().isCurrentUser()
                || !ShortcutUtil.isIceBoxPublisher(context, pojo.packageName)) return false;
        String target = pojo.targetPackage;
        if (TextUtils.isEmpty(target) || target.equals(pojo.packageName)) {
            target = recoverUniqueIceBoxTarget(context);
        }
        if (TextUtils.isEmpty(target)) return false;
        if (openThroughIceBox(context, target, asynchronous, null)) return true;
        if (AppLaunchUtils.isPackageEnabledNow(context, target)) {
            if (AppLaunchUtils.launchEnabledPackage(context, target)) {
                onExactLaunchSucceeded(context, asynchronous);
            } else {
                notifyExactShortcutUnavailable(context);
            }
            return true;
        }
        enableIceBoxTarget(context, target);
        return true;
    }

    private void enableIceBoxTarget(Context context, String target) {
        if (openThroughIceBox(context, target, true, null)) return;
        // Never enable a same-named package in the wrong Android profile.
        IceBoxShortcutIdentity identity = IceBoxShortcutIdentity.parse(pojo.getOreoId());
        if (identity != null && identity.userId >= 0
                && identity.userId != android.os.Process.myUserHandle().hashCode()) {
            Toast.makeText(context, "Open this app from its Ice Box Android profile.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        launchPending = true;
        if (IceBoxSdkBridge.available(context, pojo.packageName)) {
            if (IceBoxSdkBridge.hasPermission(context)) {
                enableViaIceBox(context, target);
                return;
            }
            if (context instanceof fr.neamar.kiss.MainActivity) {
                ((fr.neamar.kiss.MainActivity) context).requestIceBoxAccess(granted -> {
                    if (granted) enableViaIceBox(context, target);
                    else enableIceBoxTargetWithRoot(context, target, true);
                });
                return;
            }
        }
        enableIceBoxTargetWithRoot(context, target, false);
    }

    private void enableViaIceBox(Context context, String target) {
        IceBoxSdkBridge.unfreezeAsync(context, target, enabled -> {
            if (enabled) openEnabledIceBoxApp(context, target, true);
            else enableIceBoxTargetWithRoot(context, target, true);
        });
    }

    private void enableIceBoxTargetWithRoot(Context context, String target, boolean iceBoxTried) {
        if (!FrozenAppPreferences.autoEnable(context)) {
            launchPending = false;
            if (iceBoxTried) Toast.makeText(context,
                    "Allow Ice Box access to open this frozen app.", Toast.LENGTH_LONG).show();
            else showAutoEnableDisabled(context);
            return;
        }
        AppLaunchUtils.ensurePackageEnabledAsync(context, target, enabled -> {
            if (enabled) openEnabledIceBoxApp(context, target, true);
            else {
                launchPending = false;
                if (iceBoxTried) Toast.makeText(context,
                        "Ice Box could not unfreeze this app. Check its access and frozen state.",
                        Toast.LENGTH_LONG).show();
                else showUnableToEnable(context, target);
            }
        });
    }

    private void doOreoLaunch(Context context, View v, boolean asynchronous) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
            return;
        }
        LauncherApps apps = ContextCompat.getSystemService(context, LauncherApps.class);
        if (apps == null) {
            notifyExactShortcutUnavailable(context);
            return;
        }
        boolean hostPermission = false;
        try { hostPermission = apps.hasShortcutHostPermission(); }
        catch (RuntimeException e) { Log.w(TAG, "Shortcut host service unavailable", e); }

        boolean currentUser = pojo.getUserHandle().isCurrentUser();
        boolean iceBoxRoute = ShortcutUtil.isIceBoxPublisher(context, pojo.packageName);
        ShortcutInfo live = ShortcutUtil.getShortcutForLaunchState(context,
                pojo.getUserHandle().getRealHandle(), pojo.packageName, pojo.getOreoId());
        ComponentName associatedActivity = live == null ? null : live.getActivity();
        if (associatedActivity == null && currentUser && pojo.getComponentName() != null) {
            associatedActivity = ComponentName.unflattenFromString(pojo.getComponentName());
        }
        if (associatedActivity == null && currentUser) {
            associatedActivity = AppLaunchUtils.resolveSingleLauncherComponent(context, pojo.packageName);
        }
        if (associatedActivity != null
                && !pojo.packageName.equals(associatedActivity.getPackageName())) {
            associatedActivity = null;
        }
        boolean frozenPublisher = currentUser
                && !AppLaunchUtils.isPackageEnabledNow(context, pojo.packageName);
        boolean disabledActivity = currentUser && associatedActivity != null
                && !AppLaunchUtils.isActivityEnabledNow(context, associatedActivity);
        Rect sourceBounds = getViewBounds(v);

        // A pinned shortcut may be launched even if this launcher temporarily lost the Home role.
        // Try the real Android route before permission/freeze fallback gates. IceBox's own route
        // can unfreeze its target using Device Owner/Shizuku without Smart S requiring root.
        if (!frozenPublisher && !disabledActivity
                && tryExactShortcutLaunch(context, apps, sourceBounds)) {
            onExactLaunchSucceeded(context, asynchronous);
            return;
        }

        String target = iceBoxRoute && currentUser ? verifiedIceBoxTarget(context) : null;
        if (iceBoxRoute && currentUser && TextUtils.isEmpty(target)) {
            target = recoverUniqueIceBoxTarget(context);
        }
        if (iceBoxRoute && !TextUtils.isEmpty(target)) {
            // Keep exact feature IDs whenever Android still exposes them, then let Ice Box
            // recover the app alias if its own saved shortcut was withdrawn during freezing.
            if (tryOriginalIceBoxFeature(context, target, sourceBounds)) {
                onExactLaunchSucceeded(context, asynchronous);
                return;
            }
            if (openThroughIceBox(context, target, asynchronous, sourceBounds)) return;
            IceBoxShortcutIdentity identity = IceBoxShortcutIdentity.parse(pojo.getOreoId());
            boolean targetIsCurrentUser = identity == null || identity.userId < 0
                    || identity.userId == android.os.Process.myUserHandle().hashCode();
            if (targetIsCurrentUser && AppLaunchUtils.isPackageEnabledNow(context, target)) {
                if (AppLaunchUtils.launchEnabledPackage(context, target)) {
                    onExactLaunchSucceeded(context, asynchronous);
                } else {
                    notifyExactShortcutUnavailable(context);
                }
                return;
            }
            enableIceBoxTarget(context, target);
            return;
        }

        if (frozenPublisher || disabledActivity) {
            if (!FrozenAppPreferences.autoEnable(context)) {
                showAutoEnableDisabled(context);
                return;
            }
            launchPending = true;
            AppLaunchUtils.ensurePackageEnabledAsync(context, pojo.packageName,
                    associatedActivity, associatedActivity != null, enabled -> {
                        if (!enabled) {
                            launchPending = false;
                            if (IceBoxLaunchBridge.open(context, pojo.packageName,
                                    android.os.Process.myUserHandle().hashCode(), sourceBounds)) {
                                onExactLaunchSucceeded(context, true);
                                retryExactAfterIceBoxHandoff(context, sourceBounds, 0);
                            } else {
                                showUnableToEnable(context, pojo.packageName);
                            }
                            return;
                        }
                        attemptExactLaunchAfterEnable(context, sourceBounds, 0);
                    });
            return;
        }

        if (!hostPermission && !SavedShortcutRoutes.has(context, pojo)) {
            Toast.makeText(context, context.getString(R.string.shortcuts_no_host_permission),
                    Toast.LENGTH_LONG).show();
            return;
        }
        // Package replacement/unfreeze can briefly withdraw an otherwise exact ID. Retry the ID
        // on demand, never replace a conversation/feature with its application's main screen.
        launchPending = true;
        attemptExactLaunchAfterEnable(context, sourceBounds, 0);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Nullable
    private String verifiedIceBoxTarget(Context context) {
        IceBoxShortcutIdentity identity = IceBoxShortcutIdentity.parse(pojo.getOreoId());
        if (identity != null && !TextUtils.equals(identity.packageName, pojo.packageName)) {
            return identity.packageName;
        }
        String stored = pojo.targetPackage;
        if (!TextUtils.isEmpty(stored) && !TextUtils.equals(stored, pojo.packageName)) {
            // The publisher-associated package was saved before it was frozen; don't
            // reject this identity solely because PackageManager temporarily hides it.
            return stored;
        }
        // If the publisher's live shortcut is still readable, inspect the actual
        // intent chain. Missing records never trigger a name-based package guess.
        ShortcutInfo info = getShortCut(context);
        String resolved = info == null ? null : ShortcutUtil.resolveShortcutTargetPackage(context, info);
        return TextUtils.equals(resolved, pojo.packageName) ? null : resolved;
    }

    /**
     * Historical IceBox shortcuts often lack targetPackage because earlier builds
     * stored only publisher+shortcut-id. A unique matching app label is a bounded,
     * deterministic fallback; any ambiguous match is rejected.
     */
    @Nullable
    private String recoverUniqueIceBoxTarget(Context context) {
        String label = fr.neamar.kiss.utils.AppIdentityResolver.cleanIceBoxLabel(pojo.getName());
        Map<String, String> knownApps = new LinkedHashMap<>();
        try {
            DataHandler handler = KissApplication.getApplication(context).getDataHandler();
            List<fr.neamar.kiss.pojo.AppPojo> loaded = handler.getApplications();
            if (loaded != null) for (fr.neamar.kiss.pojo.AppPojo app : loaded) {
                if (app != null && app.packageName != null && app.getName() != null) {
                    knownApps.put(app.packageName, app.getName());
                }
            }
            android.os.UserManager manager = ContextCompat.getSystemService(
                    context, android.os.UserManager.class);
            if (manager != null) {
                long serial = manager.getSerialNumberForUser(android.os.Process.myUserHandle());
                if (serial >= 0L) {
                    for (AppCatalogRecord saved :
                            SmartStateStore.getRememberedApps(context, serial)) {
                        if (saved != null && saved.packageName != null
                                && saved.label != null && !knownApps.containsKey(saved.packageName)) {
                            knownApps.put(saved.packageName, saved.label);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot inspect remembered IceBox app catalog", e);
        }
        return IceBoxFallbackTargetPolicy.uniqueExactPackage(
                label, pojo.packageName, knownApps);
    }

    /** Native handoff uses only the selected, verified app identity; no label-based URI guesses. */
    private boolean openThroughIceBox(Context context, String target, boolean asynchronous,
                                      @Nullable Rect sourceBounds) {
        if (!IceBoxLaunchBridge.PACKAGE.equals(pojo.packageName)) return false;
        IceBoxShortcutIdentity identity = IceBoxShortcutIdentity.parse(pojo.getOreoId());
        int userId = identity != null && target.equals(identity.packageName) && identity.userId >= 0
                ? identity.userId : android.os.Process.myUserHandle().hashCode();
        if (!IceBoxLaunchBridge.open(context, target, userId, sourceBounds)) return false;
        launchPending = false;
        onExactLaunchSucceeded(context, asynchronous);
        if (identity != null && identity.originalShortcutId != null) {
            retryOriginalFeatureAfterIceBoxHandoff(context, target, sourceBounds, 0);
        }
        return true;
    }

    private void retryOriginalFeatureAfterIceBoxHandoff(Context context, String target,
                                                        Rect sourceBounds, int attempt) {
        if (tryOriginalIceBoxFeature(context, target, sourceBounds)
                || attempt >= ENABLED_SHORTCUT_RETRY_DELAYS_MS.length) return;
        new Handler(Looper.getMainLooper()).postDelayed(() ->
                retryOriginalFeatureAfterIceBoxHandoff(context, target, sourceBounds, attempt + 1),
                ENABLED_SHORTCUT_RETRY_DELAYS_MS[attempt]);
    }

    /** Ice Box accepted the app launch. Recover its exact feature if Android republishes it. */
    @RequiresApi(Build.VERSION_CODES.O)
    private void retryExactAfterIceBoxHandoff(Context context, Rect sourceBounds, int attempt) {
        LauncherApps apps = ContextCompat.getSystemService(context, LauncherApps.class);
        if (apps != null && tryExactShortcutLaunch(context, apps, sourceBounds)) return;
        if (attempt >= ENABLED_SHORTCUT_RETRY_DELAYS_MS.length) return;
        new Handler(Looper.getMainLooper()).postDelayed(() ->
                retryExactAfterIceBoxHandoff(context, sourceBounds, attempt + 1),
                ENABLED_SHORTCUT_RETRY_DELAYS_MS[attempt]);
    }

    private boolean tryOriginalIceBoxFeature(Context context, String target, Rect sourceBounds) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        IceBoxShortcutIdentity identity = IceBoxShortcutIdentity.parse(pojo.getOreoId());
        if (identity == null || identity.originalShortcutId == null
                || !target.equals(identity.packageName)
                || (identity.userId >= 0
                    && identity.userId != android.os.Process.myUserHandle().hashCode())
                || !AppLaunchUtils.isPackageEnabledNow(context, target)) return false;
        try {
            LauncherApps apps = ContextCompat.getSystemService(context, LauncherApps.class);
            if (apps == null) return false;
            apps.startShortcut(target, identity.originalShortcutId, sourceBounds, null,
                    pojo.getUserHandle().getRealHandle());
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Original Ice Box feature is not currently exposed", e);
            return false;
        }
    }

    private void openEnabledIceBoxApp(Context context, String target, boolean asynchronous) {
        launchPending = false;
        if (tryOriginalIceBoxFeature(context, target, null)
                || AppLaunchUtils.launchEnabledPackage(context, target)) {
            onExactLaunchSucceeded(context, asynchronous);
        } else if (!openThroughIceBox(context, target, asynchronous, null)) {
            Toast.makeText(context, "Frozen app was enabled, but could not be opened.",
                    Toast.LENGTH_LONG).show();
        }
        AppLaunchUtils.refreshLauncherAfterEnable(context, target);
    }

    /**
     * Android shortcut publication can lag after a package is enabled. Bounded retries
     * reuse the same Handler; they do not start another database scan or polling service.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private void attemptExactLaunchAfterEnable(Context context, @Nullable Rect sourceBounds,
                                               int attempt) {
        LauncherApps launcherApps = ContextCompat.getSystemService(context, LauncherApps.class);
        if (launcherApps != null
                && tryExactShortcutLaunch(context, launcherApps, sourceBounds)) {
            launchPending = false;
            onExactLaunchSucceeded(context, true);
            AppLaunchUtils.refreshLauncherAfterEnable(context, pojo.packageName);
            return;
        }

        if (SavedShortcutRoutes.has(context, pojo)) {
            SavedShortcutRoutes.open(context, pojo, opened -> {
                if (opened) {
                    launchPending = false;
                    onExactLaunchSucceeded(context, true);
                    AppLaunchUtils.refreshLauncherAfterEnable(context, pojo.packageName);
                } else {
                    scheduleExactRetry(context, sourceBounds, attempt);
                }
            });
            return;
        }
        scheduleExactRetry(context, sourceBounds, attempt);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void scheduleExactRetry(Context context, @Nullable Rect sourceBounds, int attempt) {
        if (attempt < ENABLED_SHORTCUT_RETRY_DELAYS_MS.length) {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> attemptExactLaunchAfterEnable(context, sourceBounds, attempt + 1),
                    ENABLED_SHORTCUT_RETRY_DELAYS_MS[attempt]);
            return;
        }

        launchPending = false;
        AppLaunchUtils.refreshLauncherAfterEnable(context, pojo.packageName);
        notifyExactShortcutUnavailable(context);
    }

    private void onExactLaunchSucceeded(Context context, boolean asynchronous) {
        launchSucceeded = true;
        rememberSuccessfulHistoryTarget(context);
        if (asynchronous) {
            // Result.launch() had already returned with launchSucceeded=false. Record
            // only after Android accepted the real launch, never while it is pending.
            completeAsynchronousLaunch(context);
        }
    }

    private void showAutoEnableDisabled(Context context) {
        Toast.makeText(context, "Frozen app detected. Turn on Auto-enable frozen apps when opened.",
                Toast.LENGTH_LONG).show();
    }

    private void showUnableToEnable(Context context, String packageName) {
        Toast.makeText(context, "Cannot enable " + packageName
                        + ". Check Smart S root access and the app's frozen state.",
                Toast.LENGTH_LONG).show();
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private boolean tryExactShortcutLaunch(Context context, LauncherApps launcherApps,
                                           @Nullable Rect sourceBounds) {
        ShortcutInfo shortcutInfo;
        try {
            shortcutInfo = getShortCut(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "Shortcut lookup unavailable for " + pojo.getOreoId(), e);
            shortcutInfo = null;
        }
        if (shortcutInfo != null) {
            // Pin before launch: the publisher/freezer can withdraw a dynamic ID immediately
            // when its target opens. A local catalog entry alone does not keep Android's ID alive.
            ShortcutUtil.retainShortcut(context, shortcutInfo);
            try {
                launcherApps.startShortcut(shortcutInfo, sourceBounds, null);
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to launch ShortcutInfo for " + pojo.getName(), e);
            }
        }

        try {
            launcherApps.startShortcut(pojo.packageName, pojo.getOreoId(), sourceBounds, null,
                    pojo.getUserHandle().getRealHandle());
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to launch shortcut id " + pojo.getOreoId(), e);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                PendingIntent shortcutIntent = launcherApps.getShortcutIntent(
                        pojo.packageName, pojo.getOreoId(), null,
                        pojo.getUserHandle().getRealHandle());
                if (shortcutIntent != null) {
                    sendShortcutPendingIntent(context, shortcutIntent);
                    return true;
                }
            } catch (PendingIntent.CanceledException | RuntimeException e) {
                Log.w(TAG, "Unable to send shortcut PendingIntent for " + pojo.getName(), e);
            }
        }
        return false;
    }

    private void sendShortcutPendingIntent(Context context, PendingIntent pending)
            throws PendingIntent.CanceledException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setPendingIntentBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                    ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                    : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            pending.send(context, 0, null, null, null, null, options.toBundle());
        } else {
            pending.send();
        }
    }

    /**
     * Keep an accepted shortcut launch reconstructable even if LauncherApps stops exposing the
     * dynamic shortcut immediately afterward. This mirrors DataHandler.addToHistory's freeze and
     * exclusion gates, so it never overrides an explicit history privacy choice.
     */
    private void rememberSuccessfulHistoryTarget(@NonNull Context context) {
        String historyId = pojo.getHistoryId();
        DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
        if (TextUtils.isEmpty(historyId)
                || PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("freeze-history", false)
                || dataHandler.getExcludedFromHistory().contains(historyId)) {
            return;
        }

        fr.neamar.kiss.utils.RecentLaunchTracker.remember(pojo);

        // Non-Oreo shortcuts are already database-backed by definition. Persist every successfully
        // opened Oreo shortcut independently of the separate disabled-app indexing preference so
        // History can recover WhatsApp groups, conversations and other dynamic shortcuts later.
        if (pojo.isOreoShortcut()) {
            fr.neamar.kiss.db.ShortcutRecord record = new fr.neamar.kiss.db.ShortcutRecord();
            record.name = pojo.getName();
            record.packageName = pojo.packageName;
            record.targetPackage = pojo.targetPackage;
            record.intentUri = pojo.intentUri;
            fr.neamar.kiss.db.DBHelper.insertShortcut(context, record);
        }
    }

    private void notifyExactShortcutUnavailable(Context context) {
        launchSucceeded = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pojo.isOreoShortcut()
                && !SavedShortcutRoutes.has(context, pojo) && getShortCut(context) == null) {
            Toast.makeText(context, "Android no longer exposes this saved shortcut. Add it again once to restore its exact destination.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(context, "Unable to open this exact shortcut.", Toast.LENGTH_LONG).show();
    }

    @Override
    protected boolean canAddToHistory() {
        return launchSucceeded;
    }

    @Nullable
    private ShortcutInfo getShortCut(Context context) {
        if (!pojo.isOreoShortcut()) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ShortcutUtil.getShortCut(context, pojo.getUserHandle().getRealHandle(),
                    pojo.packageName, pojo.getOreoId());
        }
        return null;
    }

    private Drawable getDrawableFromOreoShortcut(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

        if (!this.pojo.isPinned() && this.pojo.isOreoShortcut()
                && !PackageManagerUtils.isPrivateProfile(context, this.pojo.getUserHandle())) {
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

    @Override
    protected boolean isAllowedAsFavorite() {
        return !this.pojo.isDynamic() || this.pojo.isPinned();
    }

    @Override
    protected boolean canRemoveFromHistory(Context context) {
        return true;
    }

    @Override
    protected boolean canHaveCustomIcon(Context context, IconPack iconPack) {
        return isAllowedAsFavorite();
    }
}
