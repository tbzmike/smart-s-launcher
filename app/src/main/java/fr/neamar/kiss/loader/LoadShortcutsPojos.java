package fr.neamar.kiss.loader;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.Process;
import android.os.UserManager;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.TagsHandler;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.UserHandle;

public class LoadShortcutsPojos extends LoadPojos<ShortcutPojo> {

    public LoadShortcutsPojos(Context context) {
        super(context, ShortcutPojo.SCHEME);
    }

    @Override
    protected List<ShortcutPojo> doInBackground(Void... params) {
        Context context = this.context.get();
        if (context == null) return new ArrayList<>();

        List<ShortcutPojo> nonOreoPojos = fetchNonOreoPojos(context);
        List<ShortcutPojo> oreoPojos = fetchOreoPojos(context);

        List<ShortcutPojo> allPojos = new ArrayList<>(nonOreoPojos.size() + oreoPojos.size());
        allPojos.addAll(nonOreoPojos);
        allPojos.addAll(oreoPojos);
        return allPojos;
    }

    // Get Oreo+ shortcuts from Android and, when requested, merge remembered shortcuts whose
    // publisher is currently disabled/frozen and therefore no longer exposed by LauncherApps.
    private List<ShortcutPojo> fetchOreoPojos(Context context) {
        List<ShortcutPojo> oreoPojos = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return oreoPojos;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean retainDisabled = prefs.getBoolean(LoadAppPojos.PREF_INDEX_DISABLED_APPS, true);
        DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
        Set<String> excludedApps = dataHandler.getExcluded();
        Set<String> excludedShortcutApps = dataHandler.getExcludedShortcutApps();
        UserManager userManager = ContextCompat.getSystemService(context, UserManager.class);
        List<ShortcutInfo> shortcutInfos = ShortcutUtil.getAllShortcuts(context);
        Set<String> liveKeys = new HashSet<>();

        for (ShortcutInfo shortcutInfo : shortcutInfos) {
            if (isCancelled()) break;
            if (!ShortcutUtil.isShortcutVisible(context, shortcutInfo, excludedApps, excludedShortcutApps)) continue;

            ShortcutRecord shortcutRecord = ShortcutUtil.createShortcutRecord(context, shortcutInfo,
                    !shortcutInfo.isPinned());
            if (shortcutRecord == null) continue;

            String key = shortcutKey(shortcutRecord.packageName, shortcutRecord.intentUri);
            liveKeys.add(key);

            // Persist every currently visible shortcut while exhaustive disabled indexing is on.
            // Android may stop exposing it the moment its app is frozen; the DB copy is then the
            // authoritative local memory needed to keep the shortcut searchable.
            if (retainDisabled) DBHelper.insertShortcut(context, shortcutRecord);

            boolean isSuspended = PackageManagerUtils.isAppSuspended(context, shortcutInfo.getPackage(),
                    new UserHandle(context, shortcutInfo.getUserHandle()));
            boolean isQuietModeEnabled = userManager != null && userManager.isQuietModeEnabled(shortcutInfo.getUserHandle());
            boolean disabled = isSuspended || isQuietModeEnabled || !isPackageEnabled(context, shortcutInfo.getPackage());

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
            // DB records for Oreo shortcuts are normally ignored because live ShortcutInfo contains
            // richer metadata. Bring a DB record back only when Android no longer exposes it AND its
            // owning package is still installed but disabled/frozen. This avoids live duplicates.
            UserHandle currentUser = new UserHandle(context, Process.myUserHandle());
            TagsHandler tagsHandler = dataHandler.getTagsHandler();
            for (ShortcutRecord remembered : DBHelper.getShortcuts(context)) {
                if (isCancelled()) break;
                if (remembered == null || remembered.packageName == null || remembered.intentUri == null) continue;
                if (!remembered.intentUri.contains(ShortcutPojo.OREO_PREFIX)) continue;
                if (excludedShortcutApps.contains(remembered.packageName)) continue;

                String key = shortcutKey(remembered.packageName, remembered.intentUri);
                if (liveKeys.contains(key)) continue;
                if (!isInstalledButDisabled(context, remembered.packageName)) continue;

                ShortcutPojo pojo = createPojo(currentUser, remembered, tagsHandler,
                        null, true, false, true);
                oreoPojos.add(pojo);
                liveKeys.add(key);
            }
        }

        return oreoPojos;
    }

    private List<ShortcutPojo> fetchNonOreoPojos(Context context) {
        DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
        TagsHandler tagsHandler = dataHandler.getTagsHandler();
        List<ShortcutPojo> pojos = new ArrayList<>();
        List<ShortcutRecord> records = DBHelper.getShortcuts(context);

        for (ShortcutRecord shortcutRecord : records) {
            if (isCancelled()) break;
            ShortcutPojo pojo = createPojo(null, shortcutRecord, tagsHandler, null, true, false, false);
            if (!pojo.isOreoShortcut()) pojos.add(pojo);
        }
        return pojos;
    }

    private boolean isInstalledButDisabled(Context context, String packageName) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            return !isPackageEnabled(context, packageName) || PackageManagerUtils.isAppSuspended(info);
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
