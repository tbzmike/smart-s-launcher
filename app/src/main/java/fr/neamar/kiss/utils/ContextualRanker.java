package fr.neamar.kiss.utils;

import androidx.annotation.NonNull;

import java.util.Calendar;
import java.util.Locale;

/** Lightweight, on-device context boosts. Never overrides a non-match. */
public final class ContextualRanker {
    private ContextualRanker() {
    }

    public static int boost(@NonNull String name) {
        String n = name.toLowerCase(Locale.ROOT);
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int day = now.get(Calendar.DAY_OF_MONTH);
        int boost = 0;

        // Common South African salary/payday window plus month-start banking activity.
        if ((day >= 25 || day <= 3) && containsAny(n, "bank", "wallet", "pay", "money", "capitec", "fnb", "absa", "nedbank", "standard bank")) {
            boost += 28;
        }

        if (hour >= 18 && containsAny(n, "game", "music", "spotify", "youtube", "netflix", "video", "media")) {
            boost += 20;
        }

        if (hour >= 5 && hour <= 9 && containsAny(n, "calendar", "clock", "alarm", "weather", "mail", "outlook", "teams")) {
            boost += 16;
        }

        if (hour >= 21 && containsAny(n, "clock", "alarm", "sleep")) {
            boost += 12;
        }

        return Math.min(boost, 40);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
}
