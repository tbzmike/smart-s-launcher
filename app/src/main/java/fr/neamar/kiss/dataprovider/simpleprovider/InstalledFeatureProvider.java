package fr.neamar.kiss.dataprovider.simpleprovider;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import fr.neamar.kiss.R;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.fuzzy.MatchInfo;
import fr.neamar.kiss.utils.fuzzy.SmartMatcher;

/**
 * Lightweight cache of meaningful, directly launchable exported activities exposed by installed
 * and system apps. Internal/helper activities, permission-gated activities, duplicate labels and
 * ordinary launcher activities are not exposed in normal search results.
 */
public final class InstalledFeatureProvider extends SimpleProvider<SettingPojo> {
    private static final String SCHEME = "feature://";
    private static final int MAX_FEATURES = 1200;

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
        Set<String> seenComponents = new HashSet<>();
        Set<String> seenLabels = new HashSet<>();

        List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES);
        for (PackageInfo pkg : packages) {
            if (pkg.activities == null || pkg.applicationInfo == null || !pkg.applicationInfo.enabled) continue;

            CharSequence appLabelCs = pkg.applicationInfo.loadLabel(pm);
            String appLabel = appLabelCs == null ? pkg.packageName : appLabelCs.toString().trim();
            Intent launchIntent = pm.getLaunchIntentForPackage(pkg.packageName);
            String launcherClass = launchIntent != null && launchIntent.getComponent() != null
                    ? launchIntent.getComponent().getClassName() : null;

            for (ActivityInfo activity : pkg.activities) {
                if (launcherClass != null && launcherClass.equals(activity.name)) continue;
                if (!isLaunchableActivity(pm, activity)) continue;

                String componentKey = activity.packageName + "/" + activity.name;
                if (!seenComponents.add(componentKey)) continue;

                CharSequence labelCs = activity.loadLabel(pm);
                if (labelCs == null) continue;
                String label = labelCs.toString().trim();
                if (!isUsefulFeatureLabel(label, appLabel, activity)) continue;

                String labelKey = activity.packageName + "|" + label.toLowerCase(Locale.ROOT);
                if (!seenLabels.add(labelKey)) continue;

                String id = SCHEME + componentKey;
                SettingPojo pojo = new SettingPojo(id, activity.name, activity.packageName, R.drawable.setting_apps);
                pojo.setName(label + " · " + appLabel, true);
                features.add(pojo);
                if (features.size() >= MAX_FEATURES) return;
            }
        }
    }

    private boolean isUsefulFeatureLabel(String label, String appLabel, ActivityInfo activity) {
        if (label.length() < 2) return false;
        if (label.equalsIgnoreCase(appLabel)) return false;
        if (label.equalsIgnoreCase(activity.packageName)) return false;
        if (label.equalsIgnoreCase(activity.name)) return false;

        int lastDot = activity.name.lastIndexOf('.');
        if (lastDot >= 0 && label.equalsIgnoreCase(activity.name.substring(lastDot + 1))) return false;
        return true;
    }

    private boolean isLaunchableActivity(PackageManager pm, ActivityInfo activity) {
        if (activity == null || activity.applicationInfo == null) return false;
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return false;

        if (activity.permission != null && !activity.permission.isEmpty()
                && context.checkCallingOrSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        ComponentName component = new ComponentName(activity.packageName, activity.name);
        Intent probe = new Intent().setComponent(component);
        ResolveInfo resolved = pm.resolveActivity(probe, 0);
        return resolved != null
                && resolved.activityInfo != null
                && component.equals(new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name));
    }

    private boolean isLaunchableNow(PackageManager pm, SettingPojo pojo) {
        if (pojo.packageName.isEmpty() || pojo.settingName.isEmpty()) return false;
        try {
            ActivityInfo activity = pm.getActivityInfo(
                    new ComponentName(pojo.packageName, pojo.settingName),
                    0
            );
            return isLaunchableActivity(pm, activity);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public void requestResults(String query, Searcher searcher) {
        if (query == null || query.trim().length() < 2) return;

        PackageManager pm = context.getPackageManager();
        for (SettingPojo pojo : features) {
            if (!isLaunchableNow(pm, pojo)) continue;

            MatchInfo matchInfo = SmartMatcher.match(context, query, pojo.normalizedName, pojo.getName());
            if (pojo.updateMatchingRelevance(matchInfo, false) && !searcher.addResult(pojo)) return;
        }
    }

    @Override
    public boolean mayFindById(String id) {
        return id != null && id.startsWith(SCHEME);
    }
}
