package fr.neamar.kiss.ui;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import fr.neamar.kiss.R;

/** Keeps settings predictable without changing any persisted preference keys. */
public final class SettingsOrganizer {
    private SettingsOrganizer() { }

    public static void organize(PreferenceScreen screen, String rootKey) {
        if (screen == null) return;
        applyCategoryLayouts(screen);

        if (rootKey != null) return;

        order(screen, "ui-holder", 10,
                "Appearance & interface",
                "Theme, colours, global text, icons, history layout and wallpaper");
        order(screen, "history_category", 20,
                "History & timeline",
                "Recent launches, history order, counts and notification history");
        order(screen, "history_favorites", 30,
                "Favorites",
                "Favorite apps, tags and favorites bar behaviour");
        order(screen, "providers", 40,
                "Search & providers",
                "Apps, contacts, shortcuts, web search and semantic search");
        order(screen, "exclude_apps_category", 50,
                "Excluded apps",
                "Hide apps from search, history or shortcuts");
        order(screen, "ux-holder", 60,
                "Experience, gestures & animations",
                "Keyboard, gestures, visibility, tags, animations and wallpaper movement");
        order(screen, "importexport", 70,
                "Backup & transfer",
                "Import or export Smart S Launcher settings");
        order(screen, "advanced", 80,
                "System & advanced",
                "Permissions, background features, launcher role and advanced search controls");
        order(screen, "rate-app", 1000, null, null);
    }

    private static void applyCategoryLayouts(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceCategory) {
                preference.setLayoutResource(R.layout.preference_category_xp);
            }
            if (preference instanceof PreferenceGroup) {
                applyCategoryLayouts((PreferenceGroup) preference);
            }
        }
    }

    private static void order(PreferenceScreen screen, String key, int order,
                              String title, String summary) {
        Preference preference = screen.findPreference(key);
        if (preference == null) return;
        preference.setOrder(order);
        if (title != null) preference.setTitle(title);
        if (summary != null) preference.setSummary(summary);
    }
}
