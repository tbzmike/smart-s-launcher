package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.TagsHandler;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.utils.UserHandle;

/** Pixel dock sizing and shortcut tag contract on a real Android UI. */
public final class PixelDockTagRegressionTest {
    @Test public void pixelBarUsesStableFiveSlotsAndDoesNotAnimatePredictionReplacement() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String oldMode = prefs.getString("favorites-bar-mode", "standard");
        String oldLimit = prefs.getString("pixel-favorites-max-apps", "5");
        try {
            assertTrue(prefs.edit().putString("favorites-bar-mode", "pixel")
                    .putString("pixel-favorites-max-apps", "5").commit());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    assertNotNull(activity.favoritesBar);
                    assertTrue(activity.favoritesBar.getLayoutManager() instanceof GridLayoutManager);
                    assertEquals("Prediction loading must not resize the dock slots", 5,
                            ((GridLayoutManager) activity.favoritesBar.getLayoutManager()).getSpanCount());
                    assertEquals("Pixel mode must not animate away existing icons", null,
                            activity.favoritesBar.getItemAnimator());
                });
            }
        } finally {
            prefs.edit().putString("favorites-bar-mode", oldMode)
                    .putString("pixel-favorites-max-apps", oldLimit).commit();
        }
    }

    @Test public void systemAndUserShortcutsSupportTheirOwnPersistedTags() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id = "feature://com.example.regression/ScannerShortcut";
        TagsHandler tags = KissApplication.getApplication(context).getDataHandler().getTagsHandler();
        String prior = tags.getTags(id);
        try {
            tags.setTags(id, "scan barcode qr");
            SettingPojo systemShortcut = new SettingPojo(id, "ScannerShortcut",
                    "com.example.regression", R.drawable.setting_apps);
            systemShortcut.setName("System scanner shortcut", true);
            systemShortcut.setTags(tags.getTags(id));
            assertEquals("scan barcode qr", systemShortcut.getTags());
            assertNotNull(systemShortcut.getNormalizedTags());

            ShortcutRecord rec = new ShortcutRecord();
            rec.name = "User-installed scanner shortcut";
            rec.packageName = "com.example.regression";
            rec.intentUri = "intent:#Intent;action=android.intent.action.VIEW;end";
            ShortcutPojo userShortcut = new ShortcutPojo(UserHandle.OWNER, rec,
                    null, true, false, false);
            userShortcut.setTags("barcode scan");
            assertEquals("barcode scan", userShortcut.getTags());
            assertNotNull(userShortcut.getNormalizedTags());

            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    Result<?> result = Result.fromPojo(activity, systemShortcut);
                    View row = result.display(activity, null, activity.list, null);
                    TextView tagLabel = row.findViewById(R.id.item_setting_tag);
                    assertNotNull("System shortcut tags view missing", tagLabel);
                    assertTrue("Assigned tags not displayed", tagLabel.getText().toString().contains("barcode"));
                });
            }
        } finally {
            tags.setTags(id, prior);
        }
    }
}
