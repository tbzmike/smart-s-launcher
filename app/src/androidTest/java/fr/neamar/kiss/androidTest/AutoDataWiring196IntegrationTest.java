package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreference;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import fr.neamar.kiss.SettingsActivity;
import fr.neamar.kiss.SettingsFragment;
import fr.neamar.kiss.power.AutoDataController;
import fr.neamar.kiss.power.AutoDataReceiver;

/** Check Auto Data's actual Advanced settings and wakeup manifest wiring, without running su. */
public final class AutoDataWiring196IntegrationTest {
    @Test public void advancedControlsAndReceiverExistWithoutChangingMobileData() {
        android.content.Context app =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        boolean previous = prefs.getBoolean(AutoDataController.PREF_ENABLED, false);
        int oldDelay = prefs.getInt(AutoDataController.PREF_DELAY, 5);
        try {
            assertTrue(prefs.edit().putBoolean(AutoDataController.PREF_ENABLED, false)
                    .putInt(AutoDataController.PREF_DELAY, 5).commit());
            ActivityInfo receiver = app.getPackageManager().getReceiverInfo(
                    new ComponentName(app, AutoDataReceiver.class),
                    PackageManager.GET_DISABLED_COMPONENTS);
            assertNotNull("Auto Data alarm/boot receiver must be manifest registered", receiver);
            assertFalse("No other application may send a privileged Auto Data alarm",
                    receiver.exported);
            try (ActivityScenario<SettingsActivity> scenario =
                         ActivityScenario.launch(SettingsActivity.class)) {
                scenario.onActivity(activity -> {
                    activity.getSupportFragmentManager().executePendingTransactions();
                    SettingsFragment fragment = (SettingsFragment)
                            activity.getSupportFragmentManager().findFragmentByTag(
                                    SettingsActivity.ARG_SHOW_FRAGMENT);
                    assertNotNull(fragment);
                    PreferenceCategory category = fragment.findPreference(
                            "smart-s-auto-data-category");
                    assertNotNull("Auto Data must be discoverable under Advanced", category);
                    SwitchPreference toggle = fragment.findPreference(AutoDataController.PREF_ENABLED);
                    assertNotNull(toggle);
                    assertFalse(toggle.isChecked());
                    SeekBarPreference delay = fragment.findPreference(AutoDataController.PREF_DELAY);
                    assertNotNull(delay);
                    assertEquals(1, delay.getMin());
                    assertEquals(30, delay.getMax());
                    assertEquals(5, delay.getValue());
                    assertNotNull(fragment.findPreference("smart-s-auto-data-status"));
                    assertNotNull(fragment.findPreference("smart-s-auto-data-root-check"));
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        assertNotNull(fragment.findPreference(
                                "smart-s-auto-data-alarm-permission"));
                    }
                });
            }
        } catch (PackageManager.NameNotFoundException failure) {
            throw new AssertionError("Auto Data receiver missing", failure);
        } finally {
            prefs.edit().putBoolean(AutoDataController.PREF_ENABLED, previous)
                    .putInt(AutoDataController.PREF_DELAY, oldDelay).commit();
        }
    }
}
