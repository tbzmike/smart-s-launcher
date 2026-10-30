package fr.neamar.kiss;

import static org.junit.Assert.*;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.FrozenAppPreferences;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.UserHandle;

/** Cross-APK proof: publish, click, withdraw, disable/hide and open the actual saved destination. */
public final class ShortcutLaunch186IntegrationTest {
    private static final String TARGET = "com.tbzmike.shortcutfixture.target";
    private static final String ICEBOX = "com.tbzmike.shortcutfixture.icebox";
    private static final String CLASS = "com.tbzmike.shortcutfixture.FixtureTargetActivity";
    private static final String CONTROL = "com.tbzmike.shortcutfixture.FixtureControlReceiver";
    private static final String HIT = "com.tbzmike.shortcutfixture.HIT";
    private static final String[] KEYS = {FrozenAppPreferences.PREF_AUTO_ENABLE,
            FrozenAppPreferences.PREF_DETECT};
    private final LinkedBlockingQueue<Intent> hits = new LinkedBlockingQueue<>();
    private Context context;
    private ActivityScenario<MainActivity> scenario;
    private RootHandler previousRoot;
    private Field rootField;
    private Map<String, ?> previousPrefs;
    private int enableCommands;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { hits.offer(intent); }
    };

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String pkg : new String[]{TARGET, ICEBOX}) {
            shell("pm unhide --user 0 " + pkg);
            shell("pm enable --user 0 " + pkg);
            shell("pm enable --user 0 " + pkg + "/" + CLASS);
            control(pkg, "publish");
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        previousPrefs = prefs.getAll();
        prefs.edit().putBoolean(KEYS[0], true).putBoolean(KEYS[1], true).commit();
        rootField = KissApplication.class.getDeclaredField("rootHandler");
        rootField.setAccessible(true);
        previousRoot = KissApplication.getApplication(context).getRootHandler();
        // Use the emulator's authorized shell transport in place of su. The PRODUCTION enableApp
        // and enableComponent methods and their actual pm commands/state verification still run.
        rootField.set(KissApplication.getApplication(context), new RootHandler(context) {
            @Override public boolean isRootAvailable() { return true; }
            @Override protected boolean executeRootShell(String command) {
                enableCommands++;
                String last = "";
                // UiAutomation execs argv directly, unlike the production su shell. The test
                // fixture names contain no whitespace; remove shell literal quotes for that argv.
                for (String line : command.split("\n")) last = shell(line.replace("'", ""));
                return !last.contains("Exception") && !last.contains("Error:");
            }
        });
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, new IntentFilter(HIT), Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, new IntentFilter(HIT));
        }
        scenario = ActivityScenario.launch(MainActivity.class);
        LauncherApps launcher = context.getSystemService(LauncherApps.class);
        assertNotNull(launcher);
        assertTrue("CI must set Smart S as Home to exercise real Android shortcut APIs",
                launcher.hasShortcutHostPermission());
        awaitShortcut(TARGET, "fixture-A");
        awaitShortcut(ICEBOX, "fixture-A");
    }

    @After public void tearDown() throws Exception {
        if (scenario != null) {
            scenario.onActivity(a -> SearchHandler.getInstance().cancelSearch());
            scenario.close();
        }
        if (context == null) return;
        try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) { }
        if (rootField != null) rootField.set(KissApplication.getApplication(context), previousRoot);
        SharedPreferences.Editor restore = PreferenceManager.getDefaultSharedPreferences(context).edit();
        for (String key : KEYS) {
            Object old = previousPrefs == null ? null : previousPrefs.get(key);
            if (old instanceof Boolean) restore.putBoolean(key, (Boolean) old);
            else restore.remove(key);
        }
        restore.commit();
        LauncherApps launcher = context.getSystemService(LauncherApps.class);
        for (String pkg : new String[]{TARGET, ICEBOX}) {
            shell("pm unhide --user 0 " + pkg);
            shell("pm enable --user 0 " + pkg);
            shell("pm enable --user 0 " + pkg + "/" + CLASS);
            launcher.pinShortcuts(pkg, Collections.emptyList(), Process.myUserHandle());
            control(pkg, "clear");
            DBHelper.removeShortcuts(context, pkg);
        }
    }

    @Test public void openedShortcutIsPinnedWithoutLosingOtherPinsAndSurvivesDynamicRemoval()
            throws Exception {
        assertTrue(ShortcutUtil.retainShortcut(context, awaitShortcut(TARGET, "fixture-B")));
        ShortcutPojo saved = nativePojo(TARGET, "fixture-A", null);
        click(saved, false);
        assertHit("fixture-A");
        assertTrue(awaitShortcut(TARGET, "fixture-A").isPinned());
        assertTrue("Pinning A must not unpin B", awaitShortcut(TARGET, "fixture-B").isPinned());
        control(TARGET, "removeA");
        click(saved, false);
        assertHit("fixture-A");
        assertEquals("Enabled shortcuts must not invoke root", 0, enableCommands);
    }

    @Test public void disabledAndHiddenNativeShortcutActuallyUnfreezesAndOpensExactTarget()
            throws Exception {
        ShortcutPojo saved = nativePojo(TARGET, "fixture-A", null);
        click(saved, false);
        assertHit("fixture-A");
        shell("pm disable-user --user 0 " + TARGET);
        assertFalse(AppLaunchUtils.isPackageEnabledNow(context, TARGET));
        click(saved, false);
        assertHit("fixture-A");
        assertTrue(AppLaunchUtils.isPackageEnabledNow(context, TARGET));

        String mainClass = "com.tbzmike.shortcutfixture.FixtureMainActivity";
        shell("pm disable --user 0 " + TARGET + "/" + mainClass);
        assertFalse(AppLaunchUtils.isActivityEnabledNow(context, new ComponentName(TARGET, mainClass)));
        click(saved, false);
        assertHit("fixture-A");
        assertTrue(AppLaunchUtils.isActivityEnabledNow(context, new ComponentName(TARGET, mainClass)));

        String hidden = shell("pm hide --user 0 " + TARGET);
        assertFalse("Fixture must really be hidden: " + hidden,
                AppLaunchUtils.isPackageEnabledNow(context, TARGET));
        click(saved, false);
        assertHit("fixture-A");
        assertTrue("pm enable alone cannot pass this hidden-package check",
                AppLaunchUtils.isPackageEnabledNow(context, TARGET));
        assertTrue(enableCommands >= 2);
    }

    @Test public void legacyExactIntentRetainsItsDataExtrasAndEnablesDisabledComponent()
            throws Exception {
        shell("pm disable --user 0 " + TARGET + "/" + CLASS);
        ComponentName component = new ComponentName(TARGET, CLASS);
        assertFalse(AppLaunchUtils.isActivityEnabledNow(context, component));
        Intent exact = new Intent(Intent.ACTION_VIEW).setComponent(component)
                .setData(Uri.parse("fixture://legacy/oreo-shortcut/inside-data"))
                .putExtra("destination", "legacy-exact");
        ShortcutRecord record = new ShortcutRecord();
        record.name = "Legacy exact fixture";
        record.packageName = TARGET;
        record.intentUri = exact.toUri(0);
        ShortcutPojo saved = new ShortcutPojo(null, record, null, true, false, false);
        saved.setName(record.name);
        assertFalse("A marker inside legacy Intent data is not an Android shortcut ID",
                saved.isOreoShortcut());
        assertTrue(saved.getUserHandle().isCurrentUser());
        click(saved, true); // Application Context exercises NEW_TASK wiring too.
        Intent hit = assertHit("legacy-exact");
        assertEquals("fixture://legacy/oreo-shortcut/inside-data", hit.getStringExtra("data"));
        assertTrue(AppLaunchUtils.isActivityEnabledNow(context, component));
    }

    @Test public void iceboxNativeRouteWorksAndStaleWrapperUnfreezesVerifiedTarget()
            throws Exception {
        assertTrue(ShortcutUtil.isIceBoxPublisher(context, ICEBOX));
        ShortcutPojo live = nativePojo(ICEBOX, "fixture-A", TARGET);
        click(live, false);
        assertHit("fixture-A");
        assertEquals(0, enableCommands);

        ShortcutRecord stored = new ShortcutRecord();
        stored.name = "Ice Box: Shortcut Fixture";
        stored.packageName = ICEBOX;
        stored.intentUri = ShortcutPojo.OREO_PREFIX + "expired-wrapper-186";
        stored.targetPackage = TARGET;
        DBHelper.insertShortcut(context, stored);
        stored.targetPackage = null;
        DBHelper.insertShortcut(context, stored);
        assertEquals("Redacted refresh must keep the verified real app", TARGET, stored.targetPackage);
        ShortcutPojo stale = new ShortcutPojo(UserHandle.OWNER, stored, null, true, false, false);
        stale.setName(stored.name);
        shell("pm disable-user --user 0 " + TARGET);
        click(stale, false);
        assertHit("main"); // IceBox app-alias route may open that verified app; chat routes may not.
        assertTrue(AppLaunchUtils.isPackageEnabledNow(context, TARGET));
        assertTrue(enableCommands > 0);
    }

    private ShortcutPojo nativePojo(String pkg, String id, String target) throws Exception {
        ShortcutInfo info = awaitShortcut(pkg, id);
        ShortcutRecord record = ShortcutUtil.createShortcutRecord(context, info, true);
        assertNotNull(record);
        if (target != null) record.targetPackage = target;
        ShortcutPojo pojo = new ShortcutPojo(UserHandle.OWNER, record, null,
                info.isPinned(), info.isDynamic(), false);
        pojo.setName(record.name);
        return pojo;
    }

    private void click(ShortcutPojo pojo, boolean applicationContext) {
        hits.clear();
        scenario.onActivity(activity -> Result.fromPojo(activity, pojo).launch(
                applicationContext ? context.getApplicationContext() : activity,
                activity.searchEditText, activity));
    }

    private Intent assertHit(String expected) throws Exception {
        Intent hit = hits.poll(12, TimeUnit.SECONDS);
        assertNotNull("No actual destination reached for " + expected, hit);
        assertEquals(TARGET, hit.getStringExtra("publisher"));
        assertEquals("Do not replace the exact shortcut with its app home", expected,
                hit.getStringExtra("destination"));
        System.out.println("SHORTCUT_186_ACTUAL_DESTINATION " + expected);
        return hit;
    }

    private ShortcutInfo awaitShortcut(String pkg, String id) throws Exception {
        for (int i = 0; i < 60; i++) {
            ShortcutInfo found = ShortcutUtil.getShortCut(context, Process.myUserHandle(), pkg, id);
            if (found != null) return found;
            Thread.sleep(50L);
        }
        throw new AssertionError("Published shortcut unavailable " + pkg + "/" + id);
    }

    private static void control(String pkg, String mode) {
        String out = shell("am broadcast -n " + pkg + "/" + CONTROL + " --es mode " + mode);
        assertFalse(out, out.contains("Exception"));
    }

    private static String shell(String command) {
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new AssertionError("Fixture shell failed: " + command, e);
        }
    }
}
