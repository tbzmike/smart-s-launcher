package fr.neamar.kiss.dataprovider;

import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.loader.LoadShortcutsPojos;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.ShortcutUtil;
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
        for (ShortcutPojo pojo : getPojos()) {
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

    public List<ShortcutPojo> getPinnedShortcuts() {
        List<ShortcutPojo> pojos = getPojos(); List<ShortcutPojo> records = new ArrayList<>(pojos.size());
        for (ShortcutPojo pojo : pojos) { if (!pojo.isPinned()) continue; pojo.relevance = 0; records.add(pojo); }
        return records;
    }
}
