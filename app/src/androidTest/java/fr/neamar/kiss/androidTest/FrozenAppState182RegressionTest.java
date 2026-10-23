package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.FrozenAppPreferences;

/** Verifies that every frozen-state preference is live and that async enable reports real state. */
@RunWith(AndroidJUnit4.class)
public final class FrozenAppState182RegressionTest {
    @Test public void settingsAreWiredAndAsyncEnableDoesNotRequireRootForAnEnabledApp()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String[] keys = {
                FrozenAppPreferences.PREF_DETECT,
                FrozenAppPreferences.PREF_KEEP_SEARCHABLE,
                FrozenAppPreferences.PREF_GREY,
                FrozenAppPreferences.PREF_AUTO_ENABLE,
                FrozenAppPreferences.PREF_PACKAGE_MONITORING,
                FrozenAppPreferences.PREF_RECONCILE_INTERVAL,
                FrozenAppPreferences.PREF_KEEP_HISTORY,
                "root-mode"
        };
        Map<String, ?> all = prefs.getAll();
        Map<String, Object> previous = new LinkedHashMap<>();
        for (String key : keys) if (all.containsKey(key)) previous.put(key, all.get(key));

        try {
            assertTrue(prefs.edit()
                    .putBoolean(FrozenAppPreferences.PREF_DETECT, true)
                    .putBoolean(FrozenAppPreferences.PREF_KEEP_SEARCHABLE, true)
                    .putBoolean(FrozenAppPreferences.PREF_GREY, true)
                    .putBoolean(FrozenAppPreferences.PREF_AUTO_ENABLE, true)
                    .putBoolean(FrozenAppPreferences.PREF_PACKAGE_MONITORING, true)
                    .putBoolean(FrozenAppPreferences.PREF_KEEP_HISTORY, true)
                    .putString(FrozenAppPreferences.PREF_RECONCILE_INTERVAL, "package-only")
                    .putBoolean("root-mode", false).commit());
            assertTrue(FrozenAppPreferences.detect(context));
            assertTrue(FrozenAppPreferences.keepSearchable(context));
            assertTrue(FrozenAppPreferences.grey(context));
            assertTrue(FrozenAppPreferences.autoEnable(context));
            assertTrue(FrozenAppPreferences.monitorPackageChanges(context));
            assertTrue(FrozenAppPreferences.keepHistoryAndFavorites(context));
            assertEquals(-1L, FrozenAppPreferences.reconcileDelayMs(context));
            assertFalse(KissApplication.getApplication(context).getRootHandler().isRootActivated());

            assertTrue(prefs.edit().putBoolean(FrozenAppPreferences.PREF_AUTO_ENABLE, false)
                    .putString(FrozenAppPreferences.PREF_RECONCILE_INTERVAL, "60").commit());
            assertFalse(FrozenAppPreferences.autoEnable(context));
            assertEquals(60_000L, FrozenAppPreferences.reconcileDelayMs(context));

            assertTrue(prefs.edit().putBoolean("root-mode", true).commit());
            assertTrue(KissApplication.getApplication(context).getRootHandler().isRootActivated());
            // An enabled app requires no privileged operation or background polling.
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean opened = new AtomicBoolean();
            AppLaunchUtils.ensurePackageEnabledAsync(context, context.getPackageName(),
                    success -> { opened.set(success); done.countDown(); });
            assertTrue("The on-demand worker must complete", done.await(6, TimeUnit.SECONDS));
            assertTrue("Already-enabled launcher should be accepted", opened.get());
        } finally {
            SharedPreferences.Editor restore = prefs.edit();
            for (String key : keys) restore.remove(key);
            for (Map.Entry<String, Object> item : previous.entrySet()) {
                Object value = item.getValue();
                if (value instanceof Boolean) restore.putBoolean(item.getKey(), (Boolean) value);
                if (value instanceof String) restore.putString(item.getKey(), (String) value);
                if (value instanceof Integer) restore.putInt(item.getKey(), (Integer) value);
                if (value instanceof Long) restore.putLong(item.getKey(), (Long) value);
                if (value instanceof Float) restore.putFloat(item.getKey(), (Float) value);
            }
            restore.commit();
        }
    }
}
