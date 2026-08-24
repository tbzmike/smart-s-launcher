package fr.neamar.kiss.loader;

import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.UserManager;

import androidx.core.content.ContextCompat;

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

        List<ShortcutPojo> livePojos = fetchOreoPojos(context);
        Set<String> liveKeys = new HashSet<>();
        for (ShortcutPojo pojo : livePojos) liveKeys.add(pojo.packageName + "\n" + pojo.intentUri);

        List<ShortcutPojo> storedPojos = fetchStoredPojos(context, liveKeys);
        List<ShortcutPojo> allPojos = new ArrayList<>(livePojos.size() + storedPojos.size());
        allPojos.addAll(livePojos);
        allPojos.addAll(storedPojos);
        return allPojos;
    }

    private List<ShortcutPojo> fetchOreoPojos(Context context) {
        List<ShortcutPojo> oreoPojos = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
            Set<String> excludedApps = dataHandler.getExcluded();
            Set<String> excludedShortcutApps = dataHandler.getExcludedShortcutApps();
            UserManager userManager = ContextCompat.getSystemService(context, UserManager.class);
            List<ShortcutInfo> shortcutInfos = ShortcutUtil.getAllShortcuts(context);

            for (ShortcutInfo shortcutInfo : shortcutInfos) {
                if (isCancelled()) break;
                if (ShortcutUtil.isShortcutVisible(context, shortcutInfo, excludedApps, excludedShortcutApps)) {
                    ShortcutRecord record = ShortcutUtil.createShortcutRecord(context, shortcutInfo, !shortcutInfo.isPinned());
                    if (record != null) {
                        // Keep every shortcut ever discovered in the local index. Android may stop
                        // reporting it while its owning package is frozen/suspended.
                        DBHelper.insertShortcut(context, record);
                        boolean suspended = PackageManagerUtils.isAppSuspended(context, shortcutInfo.getPackage(), new UserHandle(context, shortcutInfo.getUserHandle()));
                        boolean quiet = userManager != null && userManager.isQuietModeEnabled(shortcutInfo.getUserHandle());
                        oreoPojos.add(createPojo(new UserHandle(context, shortcutInfo.getUserHandle()), record,
                                dataHandler.getTagsHandler(), ShortcutUtil.getComponentName(context, shortcutInfo),
                                shortcutInfo.isPinned(), shortcutInfo.isDynamic(), suspended || quiet));
                    }
                }
            }
        }
        return oreoPojos;
    }

    private List<ShortcutPojo> fetchStoredPojos(Context context, Set<String> liveKeys) {
        DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
        TagsHandler tagsHandler = dataHandler.getTagsHandler();
        List<ShortcutPojo> pojos = new ArrayList<>();
        for (ShortcutRecord record : DBHelper.getShortcuts(context)) {
            if (isCancelled()) break;
            if (liveKeys.contains(record.packageName + "\n" + record.intentUri)) continue;
            ShortcutPojo pojo = createPojo(null, record, tagsHandler, null, true, false, record.intentUri.contains(ShortcutPojo.OREO_PREFIX));
            pojos.add(pojo);
        }
        return pojos;
    }

    private ShortcutPojo createPojo(UserHandle userHandle, ShortcutRecord shortcutRecord, TagsHandler tagsHandler, String componentName, boolean pinned, boolean dynamic, boolean disabled) {
        ShortcutPojo pojo = new ShortcutPojo(userHandle, shortcutRecord, componentName, pinned, dynamic, disabled);
        pojo.setName(shortcutRecord.name);
        pojo.setTags(tagsHandler.getTags(pojo.id));
        return pojo;
    }
}
