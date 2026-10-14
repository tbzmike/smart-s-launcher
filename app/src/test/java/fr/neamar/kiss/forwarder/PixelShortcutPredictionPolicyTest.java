package fr.neamar.kiss.forwarder;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;

class PixelShortcutPredictionPolicyTest {
    @Test void includesAllShortcutClassesButNotAppsOrNotifications() {
        for (String value : Arrays.asList("shortcut://user-1", "shortcut://system-dynamic",
                "setting://android.settings.wifi", "feature://com.android.settings/Feature")) {
            assertTrue(PixelShortcutPredictionPolicy.isShortcutHistoryId(value), value);
        }
        for (String value : Arrays.asList("app://com.example/Main", "notification://posted",
                "contact://someone", "junk")) {
            assertFalse(PixelShortcutPredictionPolicy.isShortcutHistoryId(value), value);
        }
        assertFalse(PixelShortcutPredictionPolicy.isShortcutHistoryId(null));
    }
    @Test void shortcutRankingCompetesWithAppsByUsageNotCategory() {
        PixelFavoritesPolicy.PredictionSignal app = new PixelFavoritesPolicy.PredictionSignal(
                2, 2, 2, 9000L, 500L, 8);
        PixelFavoritesPolicy.PredictionSignal shortcut = new PixelFavoritesPolicy.PredictionSignal(
                8, 8, 8, 0L, 1000L, 35);
        assertTrue(PixelFavoritesPolicy.comparePrediction(shortcut, app) < 0,
                "Frequently launched shortcuts must be able to outrank applications");
        List<String> pinned = Arrays.asList("app://pinned");
        List<String> ranked = Arrays.asList("shortcut://scan", "app://used");
        assertEquals(Arrays.asList("app://pinned", "shortcut://scan"),
                PixelFavoritesPolicy.select(pinned, ranked, 2));
    }
}
