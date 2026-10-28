package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.utils.AppLaunchUtils;

/**
 * Root-free regression proof for normal and exact-component launch gating.
 * Actual frozen app transitions depend on root/freezer privileges and need
 * on-device verification; the test does not disable the running launcher.
 */
public final class FrozenExactShortcut184RegressionTest {
    @Test public void exactEnabledActivityIsRecognisedAndMissingActivityRejected() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ComponentName available = new ComponentName(app, MainActivity.class);
        assertTrue("Current launch target is enabled",
                AppLaunchUtils.isActivityEnabledNow(app, available));
        ComponentName missing = new ComponentName(app.getPackageName(),
                "fr.neamar.kiss.missing.Nonexistent184");
        assertFalse("Never guess another Activity when exact target is absent",
                AppLaunchUtils.isActivityEnabledNow(app, missing));

        Intent existing = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        existing.setComponent(available);
        assertTrue("Original shortcut component survives Intent serialization",
                available.equals(Intent.parseUri(existing.toUri(0), 0).getComponent()));
    }

    @Test public void alreadyEnabledComponentFinishesAsyncWithoutRoot() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ComponentName available = new ComponentName(app, MainActivity.class);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean enabled = new AtomicBoolean(false);
        AppLaunchUtils.ensurePackageEnabledAsync(app, app.getPackageName(),
                available, success -> {
                    enabled.set(success);
                    finished.countDown();
                });
        assertTrue("On-demand worker calls back", finished.await(10, TimeUnit.SECONDS));
        assertTrue("An existing enabled shortcut does not require root", enabled.get());
    }

    @Test public void missingSavedAppActivityStillAllowsPackageFallback() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ComponentName gone = new ComponentName(app.getPackageName(),
                "fr.neamar.kiss.missing.PreviousAppEntry184");
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean packageReady = new AtomicBoolean(false);
        AppLaunchUtils.ensurePackageEnabledAsync(app, app.getPackageName(),
                gone, false, success -> {
                    packageReady.set(success);
                    done.countDown();
                });
        assertTrue("App package fallback completes", done.await(10, TimeUnit.SECONDS));
        assertTrue("Renamed app Activity does not block an available package", packageReady.get());
    }

    @Test public void absentShortcutActivityCannotBeReportedAsLaunched() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ComponentName missing = new ComponentName(app.getPackageName(),
                "fr.neamar.kiss.missing.Nonexistent184");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean enabled = new AtomicBoolean(true);
        AppLaunchUtils.ensurePackageEnabledAsync(app, app.getPackageName(),
                missing, true, success -> {
                    enabled.set(success);
                    finished.countDown();
                });
        assertTrue("Invalid exact target is always resolved", finished.await(10, TimeUnit.SECONDS));
        assertFalse("Do not claim exact shortcut success when Activity is gone", enabled.get());
    }
}
