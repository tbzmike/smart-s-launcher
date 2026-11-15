package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreference;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import fr.neamar.kiss.SettingsActivity;
import fr.neamar.kiss.SettingsFragment;
import fr.neamar.kiss.SmartCategorySettingsFragment;
import fr.neamar.kiss.power.AutoDataController;
import fr.neamar.kiss.preference.UiEditLock;

/** Open actual root and Advanced Settings with locked UI and XML preference dependencies. */
public final class SettingsXml197IntegrationTest {
    @Test public void opensRootAndAdvancedWithUiLockedAndPreservesAutoDataDependency() {
        android.content.Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        boolean originalLock = prefs.getBoolean(UiEditLock.PREF_KEY, false);
        boolean originalEnabled = prefs.getBoolean(AutoDataController.PREF_ENABLED, false);
        int originalDelay = prefs.getInt(AutoDataController.PREF_DELAY, 5);
        try {
            assertTrue(prefs.edit()
                    .putBoolean(UiEditLock.PREF_KEY, true)
                    .putBoolean(AutoDataController.PREF_ENABLED, false)
                    .putInt(AutoDataController.PREF_DELAY, 19)
                    .commit());
            try (ActivityScenario<SettingsActivity> scenario =
                         ActivityScenario.launch(SettingsActivity.class)) {
                scenario.onActivity(activity -> {
                    activity.getSupportFragmentManager().executePendingTransactions();
                    SettingsFragment root = (SettingsFragment)
                            activity.getSupportFragmentManager().findFragmentByTag(
                                    SettingsActivity.ARG_SHOW_FRAGMENT);
                    assertNotNull("Settings root must open while UI is locked", root);
                    PreferenceScreen advanced = root.findPreference("advanced");
                    assertNotNull(advanced);
                    assertTrue(activity.onPreferenceStartScreen(root, advanced));
                    activity.getSupportFragmentManager().executePendingTransactions();
                    SettingsFragment nested = (SettingsFragment)
                            activity.getSupportFragmentManager().findFragmentByTag(
                                    SettingsActivity.ARG_SHOW_FRAGMENT);
                    assertTrue("Advanced must use the SmartCategory fragment",
                            nested instanceof SmartCategorySettingsFragment);
                    SwitchPreference toggle = nested.findPreference(AutoDataController.PREF_ENABLED);
                    SeekBarPreference delay = nested.findPreference(AutoDataController.PREF_DELAY);
                    assertNotNull("Auto Data switch must exist in Advanced", toggle);
                    assertNotNull("Auto Data slider must exist in Advanced", delay);
                    assertEquals(1, delay.getMin());
                    assertEquals(30, delay.getMax());
                    assertEquals(19, delay.getValue());
                    assertFalse(toggle.isChecked());
                    assertFalse("Delay must be disabled when Auto Data is off", delay.isEnabled());
                    assertNotNull(nested.findPreference("smart-s-auto-data-root-check"));
                    assertNotNull(nested.findPreference("smart-s-auto-data-status"));
                    assertNotNull(nested.findPreference("smart-s-auto-data-alarm-permission"));
                });
            }
        } finally {
            assertTrue(prefs.edit().putBoolean(UiEditLock.PREF_KEY, originalLock)
                    .putBoolean(AutoDataController.PREF_ENABLED, originalEnabled)
                    .putInt(AutoDataController.PREF_DELAY, originalDelay).commit());
        }
    }
}
