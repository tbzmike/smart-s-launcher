package fr.neamar.kiss.dataprovider;

import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.UserManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.activitylauncher.ActivityLauncherStore;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.loader.LoadShortcutsPojos;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.UserHandle;
import fr.neamar.kiss.utils.fuzzy.MatchInfo;
import fr.neamar.kiss.utils.fuzzy.SmartMatcher;

public class ShortcutsProvider extends Provider<ShortcutPojo> {
    private static boolean notifiedKissNotDefaultLauncher = false;
    protected static final String TAG = ShortcutsProvider.class.getSimpleName();

    @Override
    public void onCreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final LauncherApps launcher = ContextCompat.getSystemService(this, LauncherApps.class);
            assert launcher != null;
            launcher.registerCallback(new LauncherAppsCallback() {
                @Override
                public void onShortcutsChanged(@NonNull String packageName, @NonNull List<ShortcutInfo> shortcuts, @NonNull android.os.UserHandle user) {
                    if (isAnyShortcutVisible(shortcuts)) {
                        Log.d(TAG, "Shortcuts changed for " + packageName);
                        KissApplication.getApplication(ShortcutsProvider.this).getDataHandler().reloadShortcuts();
                    }
                }
                private boolean isAnyShortcutVisible(List<ShortcutInfo> shortcuts) {
                    DataHandler dataHandler = KissApplication.getApplication(ShortcutsProvider.this).getDataHandler();
                    Set<String> excludedApps = dataHandler.getExcluded();
                    Set<String> excludedShortcutApps = dataHandler.getExcludedShortcutApps();
                    for (ShortcutInfo shortcutInfo : shortcuts) {
                        if (ShortcutUtil.isShortcutVisible(ShortcutsProvider.this, shortcutInfo, excludedApps, excludedShortcutApps)) return true;
                    }
                    return false;
                }
            });
        }
        super.onCreate();
    }

    @Override
    public void reload() {
        super.reload();
        try { this.initialize(new LoadShortcutsPojos(this)); }
        catch (IllegalStateException e) {
            if (!notifiedKissNotDefaultLauncher) Toast.makeText(this, R.string.unable_to_initialize_shortcuts, Toast.LENGTH_LONG).show();
            notifiedKissNotDefaultLauncher = true;
            Log.w(TAG, "Unable to initialize shortcuts", e);
        }
    }

    @Override
    public void requestResults(String query, Searcher searcher) {
        Set<String> excludedFavoriteIds = KissApplication.getApplication(this).getDataHandler().getExcludedFavorites();
        int checked = 0;
        for (ShortcutPojo pojo : getPojos()) {
            if ((checked++ & 31) == 0 && searcher.isCancelled()) return;
            if (excludedFavoriteIds.contains(pojo.getFavoriteId())) continue;
            MatchInfo matchInfo = SmartMatcher.match(this, query, pojo.normalizedName, pojo.getName());
            boolean match = pojo.updateMatchingRelevance(matchInfo, false);
            if (pojo.getNormalizedTags() != null) {
                matchInfo = SmartMatcher.match(this, query, pojo.getNormalizedTags(), pojo.getName());
                match = pojo.updateMatchingRelevance(matchInfo, match);
            }
            if (match && !searcher.addResult(pojo)) return;
        }
    }

    /**
     * History, favorites and the fast Home preview all resolve identities through DataHandler.
     * A dynamic shortcut may disappear from LauncherApps while its saved history identity remains
     * valid, so provider lookup must fall back to the permanent shortcut catalog instead of making
     * each History renderer implement a different recovery path.
     */
    @Override
    public ShortcutPojo findById(String id) {
        ShortcutPojo live = super.findById(id);
        if (live != null || id == null || !id.startsWith(ShortcutPojo.SCHEME)) return live;

        DataHandler dataHandler = KissApplication.getApplication(this).getDataHandler();
        UserManager userManager = ContextCompat.getSystemService(this, UserManager.class);

        for (ShortcutRecord record : DBHelper.getShortcuts(this)) {
            if (record == null || record.packageName == null || record.intentUri == null) continue;

            if (ActivityLauncherStore.isManaged(record)) {
                String stableId = ActivityLauncherStore.stablePojoId(record);
                if (!id.equals(stableId)) continue;

                ShortcutPojo recovered = new ShortcutPojo(UserHandle.OWNER, record, null,
                        true, false, false, stableId);
                recovered.setName(ActivityLauncherStore.displayLabel(this, record));
                recovered.setTags(dataHandler.getTagsHandler().getTags(recovered.id));
                return recovered;
            }

            if (!record.intentUri.contains(ShortcutPojo.OREO_PREFIX)) {
                String stableId = ShortcutUtil.generateShortcutId(null, record);
                if (!id.equals(stableId)) continue;

                ShortcutPojo recovered = new ShortcutPojo(null, record, null,
                        true, false, false);
                recovered.setName(record.name);
                recovered.setTags(dataHandler.getTagsHandler().getTags(recovered.id));
                return recovered;
            }

            if (userManager == null) continue;
            for (android.os.UserHandle profile : userManager.getUserProfiles()) {
                UserHandle user = new UserHandle(this, profile);
                if (!id.equals(ShortcutUtil.generateShortcutId(user, record))) continue;

                ShortcutPojo recovered = new ShortcutPojo(user, record, null,
                        true, false, true);
                recovered.setName(record.name);
                recovered.setTags(dataHandler.getTagsHandler().getTags(recovered.id));
                return recovered;
            }
        }
        return null;
    }

    public List<ShortcutPojo> getPinnedShortcuts() {
        List<ShortcutPojo> pojos = getPojos(); List<ShortcutPojo> records = new ArrayList<>(pojos.size());
        for (ShortcutPojo pojo : pojos) { if (!pojo.isPinned()) continue; pojo.relevance = 0; records.add(pojo); }
        return records;
    }
}
