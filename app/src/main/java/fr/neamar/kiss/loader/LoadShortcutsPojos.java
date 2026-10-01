package fr.neamar.kiss.loader;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.Process;
import android.os.UserManager;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.TagsHandler;
import fr.neamar.kiss.activitylauncher.ActivityLauncherStore;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.utils.FrozenAppPreferences;
import fr.neamar.kiss.utils.LauncherScrollWorkGate;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.UserHandle;

public class LoadShortcutsPojos extends LoadPojos<ShortcutPojo> {

    public LoadShortcutsPojos(Context context) {
        super(context, ShortcutPojo.SCHEME);
    }

    @Override
    protected List<ShortcutPojo> doInBackground(Void... params) {
        // ShortcutManager can return a large binder payload. Run all parsing/database catalog work
        // at background priority so a provider refresh cannot compete with scroll rendering.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        if (!waitForScrollIdle()) return new ArrayList<>();
        Context context = this.context.get();
        if (context == null) return new ArrayList<>();

        // Read the remembered shortcut catalog once for the whole provider pass. The previous
        // path queried it separately for legacy and Oreo shortcuts and then issued another SELECT
        // for every exposed shortcut before deciding whether an UPDATE was needed.
        List<ShortcutRecord> storedRecords = DBHelper.getShortcuts(context);
        List<ShortcutPojo> nonOreoPojos = fetchNonOreoPojos(context, storedRecords);
        List<ShortcutPojo> oreoPojos = fetchOreoPojos(context, storedRecords);

        List<ShortcutPojo> allPojos = new ArrayList<>(nonOreoPojos.size() + oreoPojos.size());
        allPojos.addAll(nonOreoPojos);
        allPojos.addAll(oreoPojos);
        return allPojos;
    }

    // Get Oreo+ shortcuts from Android and, when requested, merge the permanent remembered
    // shortcut catalog. Once a shortcut has been discovered it remains searchable while its app is
    // still installed, even if freezing/disabling the app makes LauncherApps stop exposing it.
    private List<ShortcutPojo> fetchOreoPojos(
            Context context, List<ShortcutRecord> storedRecords) {
        List<ShortcutPojo> oreoPojos = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return oreoPojos;

        boolean retainDisabled = FrozenAppPreferences.detect(context);
        DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
        Set<String> excludedApps = dataHandler.getExcluded();
        Set<String> excludedShortcutApps = dataHandler.getExcludedShortcutApps();
        UserManager userManager = ContextCompat.getSystemService(context, UserManager.class);
        if (!waitForScrollIdle()) return oreoPojos;
        List<ShortcutInfo> shortcutInfos = ShortcutUtil.getAllShortcuts(context);
        Set<String> liveKeys = new HashSet<>();
        Map<String, ShortcutRecord> storedByKey = new HashMap<>();
        if (retainDisabled) {
            for (ShortcutRecord stored : storedRecords) {
                if (stored == null || stored.packageName == null || stored.intentUri == null) continue;
                storedByKey.put(shortcutKey(stored.packageName, stored.intentUri), stored);
            }
        }

        for (ShortcutInfo shortcutInfo : shortcutInfos) {
            if (isCancelled() || !waitForScrollIdle()) break;

            boolean packageDisabled = !isPackageEnabled(context, shortcutInfo.getPackage());
            boolean normalVisible = ShortcutUtil.isShortcutVisible(context, shortcutInfo,
                    excludedApps, excludedShortcutApps);
            boolean disabledVisible = retainDisabled
                    && packageDisabled
                    && !excludedShortcutApps.contains(shortcutInfo.getPackage());
            if (!normalVisible && !disabledVisible) continue;

            ShortcutRecord shortcutRecord = ShortcutUtil.createShortcutRecord(context, shortcutInfo,
                    !shortcutInfo.isPinned());
            if (shortcutRecord == null) continue;

            String key = shortcutKey(shortcutRecord.packageName, shortcutRecord.intentUri);
            liveKeys.add(key);

            // Permanent catalog: save every exposed shortcut, not only pinned shortcuts. This is
            // what lets the exact shortcut survive a later IceBox/pm disable operation.
            if (retainDisabled) {
                ShortcutRecord previous = storedByKey.get(key);
                String previousTarget = previous == null || previous.targetPackage == null
                        ? "" : previous.targetPackage;
                String nextTarget = shortcutRecord.targetPackage == null
                        ? "" : shortcutRecord.targetPackage;
                boolean changed = previous == null
                        || !TextUtils.equals(previous.name, shortcutRecord.name)
                        || !TextUtils.equals(previousTarget, nextTarget);
                if (changed) {
                    DBHelper.insertShortcut(context, shortcutRecord);
                    storedByKey.put(key, shortcutRecord);
                }
            }

            boolean isSuspended = PackageManagerUtils.isAppSuspended(context, shortcutInfo.getPackage(),
                    new UserHandle(context, shortcutInfo.getUserHandle()));
            boolean isQuietModeEnabled = userManager != null
                    && userManager.isQuietModeEnabled(shortcutInfo.getUserHandle());
            boolean disabled = isSuspended || isQuietModeEnabled || packageDisabled || !shortcutInfo.isEnabled();

            ShortcutPojo pojo = createPojo(
                    new UserHandle(context, shortcutInfo.getUserHandle()),
                    shortcutRecord,
                    dataHandler.getTagsHandler(),
                    ShortcutUtil.getComponentName(context, shortcutInfo),
                    shortcutInfo.isPinned(),
                    shortcutInfo.isDynamic(),
                    disabled
            );
            oreoPojos.add(pojo);
        }

        if (retainDisabled) {
            UserHandle currentUser = new UserHandle(context, Process.myUserHandle());
            TagsHandler tagsHandler = dataHandler.getTagsHandler();
            for (ShortcutRecord remembered : new ArrayList<>(storedByKey.values())) {
                if (isCancelled() || !waitForScrollIdle()) break;
                if (remembered == null || remembered.packageName == null || remembered.intentUri == null) continue;
                if (!remembered.intentUri.contains(ShortcutPojo.OREO_PREFIX)) continue;
                if (excludedShortcutApps.contains(remembered.packageName)) continue;

                String key = shortcutKey(remembered.packageName, remembered.intentUri);
                if (liveKeys.contains(key)) continue;

                // Keep remembered shortcuts for as long as the owning package remains installed.
                // For a normal shortcut, disabled state follows the shortcut publisher package.
                // IceBox is different: its shortcut is an external launch route and must keep
                // following IceBox's own behavior instead of inheriting Smart S's frozen-target
                // treatment merely because the wrapped app is disabled.
                if (!isPackageInstalled(context, remembered.packageName)) continue;
                boolean disabled = !isPackageEnabled(context, remembered.packageName);
                if (ShortcutUtil.isIceBoxPublisher(context, remembered.packageName)) {
                    disabled = false;
                }

                ShortcutPojo pojo = createPojo(currentUser, remembered, tagsHandler,
                        null, true, false, disabled);
                oreoPojos.add(pojo);
                liveKeys.add(key);
            }
        }

        return oreoPojos;
    }

    private List<ShortcutPojo> fetchNonOreoPojos(
            Context context, List<ShortcutRecord> records) {
        DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
        TagsHandler tagsHandler = dataHandler.getTagsHandler();
        List<ShortcutPojo> pojos = new ArrayList<>();

        for (ShortcutRecord shortcutRecord : records) {
            if (isCancelled() || !waitForScrollIdle()) break;
            ShortcutPojo pojo;
            if (ActivityLauncherStore.isManaged(shortcutRecord)) {
                pojo = new ShortcutPojo(UserHandle.OWNER, shortcutRecord, null,
                        true, false, false, ActivityLauncherStore.stablePojoId(shortcutRecord));
                pojo.setName(ActivityLauncherStore.displayLabel(context, shortcutRecord));
                pojo.setTags(tagsHandler.getTags(pojo.id));
            } else {
                pojo = createPojo(null, shortcutRecord, tagsHandler, null, true, false, false);
            }
            if (!pojo.isOreoShortcut()) pojos.add(pojo);
        }
        return pojos;
    }

    private boolean waitForScrollIdle() {
        while (LauncherScrollWorkGate.isScrolling() && !isCancelled()) {
            try {
                Thread.sleep(40L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isCancelled();
    }

    private boolean isPackageInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    private boolean isPackageEnabled(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            if (!info.enabled || PackageManagerUtils.isAppSuspended(info)) return false;
            int state = pm.getApplicationEnabledSetting(packageName);
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
            return false;
        }
    }

    private String shortcutKey(String packageName, String intentUri) {
        return packageName + "|" + intentUri;
    }

    private ShortcutPojo createPojo(UserHandle userHandle, ShortcutRecord shortcutRecord,
                                    TagsHandler tagsHandler, String componentName,
                                    boolean pinned, boolean dynamic, boolean disabled) {
        ShortcutPojo pojo = new ShortcutPojo(userHandle, shortcutRecord, componentName, pinned, dynamic, disabled);
        pojo.setName(shortcutRecord.name);
        pojo.setTags(tagsHandler.getTags(pojo.id));
        return pojo;
    }
}
