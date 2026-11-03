package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.UIColors;
import fr.neamar.kiss.ui.GlobalTextStyler;
import fr.neamar.kiss.ui.SearchQuickActionStyle;
import fr.neamar.kiss.ui.SmartTextAppearance;

public final class SearchQuickActionAppearance189Test {
    @Test public void wallpaperTintsRemainReadableOverLightAndDarkWallpapers() {
        for (int wallpaper : new int[]{Color.BLACK, Color.WHITE, Color.RED, Color.BLUE,
                Color.GREEN, 0xFFFFE96A, 0xFF875040}) {
            int surface = SearchQuickActionStyle.surfaceForWallpaperColor(wallpaper);
            assertTrue("Wallpaper remains visible through the button", Color.alpha(surface) < 255);
            assertTrue("Button surface is not transparent", Color.alpha(surface) >= 200);
            assertTrue("White text contrasts over a white wallpaper",
                    ColorUtils.calculateContrast(Color.WHITE,
                            ColorUtils.compositeColors(surface, Color.WHITE)) >= 4.5);
            assertTrue("White text contrasts over a dark wallpaper",
                    ColorUtils.calculateContrast(Color.WHITE,
                            ColorUtils.compositeColors(surface, Color.BLACK)) >= 4.5);
        }
        assertNotEquals("Different wallpaper colours produce different tints",
                SearchQuickActionStyle.surfaceForWallpaperColor(Color.RED),
                SearchQuickActionStyle.surfaceForWallpaperColor(Color.BLUE));
    }

    @Test public void actualButtonsStayReadableWithGlobalColourAndInversion() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HistoryTestSupport188.configure(app);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().putBoolean("transparent-search", true)
                .putString(GlobalTextStyler.PREF_GLOBAL_TEXT_COLOR, UIColors.colorToString(Color.WHITE))
                .putBoolean(SmartTextAppearance.PREF_TEXT_COLOR_INVERTER, false).commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            HistoryTestSupport188.awaitUi(scenario, "Providers loaded", activity ->
                    KissApplication.getApplication(activity).getDataHandler().isAllProvidersLoaded());
            scenario.onActivity(activity -> activity.searchEditText.setText("wallpaper189"));
            HistoryTestSupport188.awaitUi(scenario, "Both search buttons are laid out", activity ->
                    activity.findViewById(R.id.searchDatabaseNow).isShown()
                            && activity.findViewById(R.id.searchDatabaseNow).getWidth() > 0
                            && activity.findViewById(R.id.searchNotificationHistory).isShown());
            Thread.sleep(200L);
            scenario.onActivity(activity -> assertButtons(activity));

            prefs.edit().putString(GlobalTextStyler.PREF_GLOBAL_TEXT_COLOR,
                    UIColors.colorToString(Color.BLACK)).commit();
            HistoryTestSupport188.awaitUi(scenario, "Global text colour still changes other launcher text",
                    activity -> activity.searchEditText.getCurrentTextColor() == Color.BLACK);
            scenario.onActivity(activity -> assertButtons(activity));

            prefs.edit().putBoolean(SmartTextAppearance.PREF_TEXT_COLOR_INVERTER, true).commit();
            HistoryTestSupport188.awaitUi(scenario, "Global text inversion still applies elsewhere",
                    activity -> activity.searchEditText.getCurrentTextColor() == Color.WHITE);
            scenario.onActivity(activity -> {
                assertButtons(activity);
                activity.searchEditText.setText("wallpaper189 screenshot");
                // Exercise a warm wallpaper tint on the actual rendered button backgrounds.
                int warm = SearchQuickActionStyle.surfaceForWallpaperColor(0xFFB45B3E);
                SearchQuickActionStyle.apply(activity.findViewById(R.id.searchDatabaseNow), warm);
                SearchQuickActionStyle.apply(activity.findViewById(R.id.searchNotificationHistory), warm);
                assertButtons(activity);
            });
            HistoryTestSupport188.awaitUi(scenario, "Both buttons remain visible above the keyboard", activity ->
                    activity.findViewById(R.id.searchDatabaseNow).isShown()
                            && activity.findViewById(R.id.searchNotificationHistory).isShown());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            HistoryTestSupport188.screenshot(app, "search-buttons-wallpaper-189");
        } finally {
            prefs.edit().remove(GlobalTextStyler.PREF_GLOBAL_TEXT_COLOR)
                    .remove(SmartTextAppearance.PREF_TEXT_COLOR_INVERTER)
                    .remove("transparent-search").commit();
        }
    }

    private static void assertButtons(MainActivity activity) {
        for (int id : new int[]{R.id.searchDatabaseNow, R.id.searchNotificationHistory}) {
            TextView button = activity.findViewById(id);
            assertTrue("Button remains clickable", button.isClickable());
            assertFalse("Button label is present", button.getText().toString().trim().isEmpty());
            assertEquals("The labels stay light even when launcher text is inverted",
                    Color.WHITE, button.getCurrentTextColor());
            Drawable background = button.getBackground();
            assertNotNull(background);
            Bitmap sample = Bitmap.createBitmap(button.getWidth(), button.getHeight(), Bitmap.Config.ARGB_8888);
            Rect original = new Rect(background.getBounds());
            background.setBounds(0, 0, sample.getWidth(), sample.getHeight());
            background.draw(new Canvas(sample));
            background.setBounds(original);
            int surface = sample.getPixel(sample.getWidth() / 2, sample.getHeight() / 2);
            sample.recycle();
            assertTrue("The actual background is translucent", Color.alpha(surface) >= 200
                    && Color.alpha(surface) < 255);
            assertTrue("Actual button contrasts with the brightest wallpaper",
                    ColorUtils.calculateContrast(Color.WHITE,
                            ColorUtils.compositeColors(surface, Color.WHITE)) >= 4.5);
        }
    }
}
