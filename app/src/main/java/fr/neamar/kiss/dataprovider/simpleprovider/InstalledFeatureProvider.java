package fr.neamar.kiss.dataprovider.simpleprovider;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

import fr.neamar.kiss.R;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.fuzzy.MatchInfo;
import fr.neamar.kiss.utils.fuzzy.SmartMatcher;

/**
 * Lightweight cache of meaningful exported activities exposed by installed and system apps.
 * Only enabled, exported, labelled activities are indexed. Main launcher activities are skipped
 * so ordinary app results are not duplicated.
 */
public final class InstalledFeatureProvider extends SimpleProvider<SettingPojo> {
    private static final String SCHEME = "feature://";
    private static final int MAX_FEATURES = 2500;

    private final Context context;
    private final List<SettingPojo> features = new ArrayList<>();

    public InstalledFeatureProvider(Context context) {
        this.context = context.getApplicationContext();
        buildIndex();
    }

    private void buildIndex() {
        features.clear();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean("enable-deep-activities", true)) return;

        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES);
        for (PackageInfo pkg : packages) {
            if (pkg.activities == null || pkg.applicationInfo == null || !pkg.applicationInfo.enabled) continue;

            CharSequence appLabelCs = pkg.applicationInfo.loadLabel(pm);
            String appLabel = appLabelCs == null ? pkg.packageName : appLabelCs.toString().trim();
            Intent launchIntent = pm.getLaunchIntentForPackage(pkg.packageName);
            String launcherClass = launchIntent != null && launchIntent.getComponent() != null
                    ? launchIntent.getComponent().getClassName() : null;

            for (ActivityInfo activity : pkg.activities) {
                if (!activity.exported || !activity.enabled) continue;
                if (launcherClass != null && launcherClass.equals(activity.name)) continue;

                CharSequence labelCs = activity.loadLabel(pm);
                if (labelCs == null) continue;
                String label = labelCs.toString().trim();
                if (label.isEmpty() || label.equalsIgnoreCase(appLabel)) continue;

                Intent probe = new Intent().setClassName(activity.packageName, activity.name);
                ResolveInfo resolved = pm.resolveActivity(probe, 0);
                if (resolved == null || resolved.activityInfo == null) continue;

                String id = SCHEME + activity.packageName + "/" + activity.name;
                SettingPojo pojo = new SettingPojo(id, activity.name, activity.packageName, R.drawable.setting_apps);
                pojo.setName(label + " · " + appLabel, true);
                features.add(pojo);
                if (features.size() >= MAX_FEATURES) return;
            }
        }
    }

    @Override
    public void requestResults(String query, Searcher searcher) {
        if (query == null || query.trim().length() < 2) return;
        for (SettingPojo pojo : features) {
            MatchInfo matchInfo = SmartMatcher.match(context, query, pojo.normalizedName, pojo.getName());
            if (pojo.updateMatchingRelevance(matchInfo, false) && !searcher.addResult(pojo)) return;
        }
    }

    @Override
    public boolean mayFindById(String id) {
        return id != null && id.startsWith(SCHEME);
    }
}
