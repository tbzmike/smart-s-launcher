package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Process;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.utils.IceBoxFallbackTargetPolicy;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.broadcast.PackageAddedRemovedHandler;
import fr.neamar.kiss.utils.UserHandle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Checks startup/recreation and safe shortcut enumeration during transitions.
 * The emulator has no actual IceBox/root privilege; those need real-device testing.
 */
public final class LauncherStability183RegressionTest {
    @Test public void homeRecreatesAndShortcutProviderDoesNotCrash() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull("History adapter still available", activity.adapter);
                assertNotNull("Search field still available", activity.searchEditText);
                assertNotNull(ShortcutUtil.getShortcuts(activity, "com.catchingnow.icebox"));
                // Missing exact Android shortcut must safely return null, not crash the launcher.
                assertTrue(ShortcutUtil.getShortCut(activity, Process.myUserHandle(),
                        activity.getPackageName(), "non-existent-183") == null);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertNotNull("Adapter survives recreation", activity.adapter);
                assertNotNull("Search survives recreation", activity.searchEditText);
            });
        }
    }

    @Test public void legacyIceBoxTargetsNeverUseAmbiguousLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("com.catchingnow.icebox", "IceBox");
        labels.put("app.first", "Frozen Mail");
        assertTrue("app.first".equals(
                IceBoxFallbackTargetPolicy.uniqueExactPackage(
                        "Frozen Mail", "com.catchingnow.icebox", labels)));
        labels.put("app.second", "Frozen Mail");
        assertTrue("Duplicate target label must reject fallback",
                IceBoxFallbackTargetPolicy.uniqueExactPackage(
                        "Frozen Mail", "com.catchingnow.icebox", labels) == null);
    }
    @Test public void malformedPackageEventsDoNotCrashHome() {
        android.content.Context app =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .getTargetContext();
        PackageAddedRemovedHandler.handleEvent(app, Intent.ACTION_PACKAGE_CHANGED,
                new String[]{null, "", "   "}, UserHandle.OWNER, false);
        PackageAddedRemovedHandler.handleEvent(app, Intent.ACTION_PACKAGE_CHANGED,
                new String[]{app.getPackageName(), null}, UserHandle.OWNER, false);
    }

}
