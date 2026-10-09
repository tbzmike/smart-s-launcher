package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import fr.neamar.kiss.R;
import fr.neamar.kiss.searcher.AppSourceMetadataUpdater;
import fr.neamar.kiss.searcher.SemanticHnswIndex;

/** Builds a searchable index across legacy settings, Smart S settings and dynamic settings. */
public final class SettingsSearchIndex {
    private static final String APP_NS = "http://schemas.android.com/apk/res-auto";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    public enum Destination {
        STANDARD,
        SMART_FEATURES,
        SMART_SECTION,
        BATTERY_MONITOR,
        INDEXING_SETTINGS,
        APP_USAGE
    }

    public static final class Entry {
        public final String title;
        public final String summary;
        public final String key;
        public final String rootKey;
        public final Destination destination;
        public final String section;
        public final boolean opensScreen;

        Entry(String title, String summary, String key, String rootKey,
              Destination destination, String section, boolean opensScreen) {
            this.title = title;
            this.summary = summary;
            this.key = key;
            this.rootKey = rootKey;
            this.destination = destination;
            this.section = section;
            this.opensScreen = opensScreen;
        }

        String searchableText() {
            return (title + " " + summary + " " + nullToEmpty(key) + " "
                    + nullToEmpty(rootKey) + " " + nullToEmpty(section)).toLowerCase(Locale.ROOT);
        }
    }

    private SettingsSearchIndex() { }

    @NonNull
    public static List<Entry> search(@NonNull Context context, @NonNull String query) {
        String clean = query.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) return Collections.emptyList();

        List<Entry> all = build(context);
        String[] tokens = clean.split("\\s+");
        List<Entry> results = new ArrayList<>();
        for (Entry entry : all) {
            String haystack = entry.searchableText();
            boolean matches = true;
            for (String token : tokens) {
                if (!haystack.contains(token)) {
                    matches = false;
                    break;
                }
            }
            if (matches) results.add(entry);
        }
        results.sort(Comparator
                .comparingInt((Entry entry) -> rank(entry, clean))
                .thenComparing(entry -> entry.title.toLowerCase(Locale.ROOT)));
        return results;
    }

    private static int rank(Entry entry, String query) {
        String title = entry.title.toLowerCase(Locale.ROOT);
        if (title.equals(query)) return 0;
        if (title.startsWith(query)) return 1;
        if (title.contains(query)) return 2;
        return 3;
    }

    private static List<Entry> build(Context context) {
        List<Entry> entries = new ArrayList<>();
        parsePreferenceXml(context, R.xml.preferences, Destination.STANDARD, entries);
        parsePreferenceXml(context, R.xml.preferences_smart_features, Destination.SMART_FEATURES, entries);
        addDynamicEntries(entries);
        return entries;
    }

    private static void parsePreferenceXml(Context context, int xmlRes,
                                           Destination destination, List<Entry> out) {
        Resources resources = context.getResources();
        try (XmlResourceParser parser = resources.getXml(xmlRes)) {
            Deque<ScreenFrame> screens = new ArrayDeque<>();
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    boolean isScreen = tag != null && tag.endsWith("PreferenceScreen");
                    String key = attribute(parser, resources, "key");
                    String title = attribute(parser, resources, "title");
                    String summary = attribute(parser, resources, "summary");
                    String parentScreen = screens.isEmpty() ? null : screens.peek().key;

                    if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(title)) {
                        out.add(new Entry(title, nullToEmpty(summary), key, parentScreen,
                                destination, null, isScreen));
                    }

                    if (isScreen && !TextUtils.isEmpty(key)) {
                        screens.push(new ScreenFrame(key, parser.getDepth()));
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if (tag != null && tag.endsWith("PreferenceScreen")
                            && !screens.isEmpty() && screens.peek().depth == parser.getDepth()) {
                        screens.pop();
                    }
                }
            }
        } catch (Exception ignored) {
            // Search remains useful even if an optional preference resource cannot be parsed.
        }
    }

    private static String attribute(XmlResourceParser parser, Resources resources, String name) {
        int resId = parser.getAttributeResourceValue(APP_NS, name, 0);
        if (resId == 0) resId = parser.getAttributeResourceValue(ANDROID_NS, name, 0);
        if (resId != 0) {
            try { return resources.getText(resId).toString(); }
            catch (Resources.NotFoundException ignored) { }
        }
        String value = parser.getAttributeValue(APP_NS, name);
        if (value == null) value = parser.getAttributeValue(ANDROID_NS, name);
        return value == null ? "" : value;
    }

    private static void addDynamicEntries(List<Entry> out) {
        // SmartCategorySettingsFragment / GlobalTextPreferences entries created at runtime.
        dynamic(out, "Global text colour override", "Change launcher-wide text colour", 
                GlobalTextStyler.PREF_GLOBAL_TEXT_COLOR, "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Use global text boldness", "Enable one launcher-wide text weight", 
                GlobalTextStyler.PREF_GLOBAL_TEXT_WEIGHT_ENABLED, "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Global text boldness", "Set global text weight from thin to heavy", 
                GlobalTextStyler.PREF_GLOBAL_TEXT_WEIGHT, "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Text colour inverter", "Invert rendered text for wallpaper readability", 
                SmartTextAppearance.PREF_TEXT_COLOR_INVERTER, "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Default font family", "Global fallback font including search and result text", 
                "smart-default-text-font-family", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Default primary text size", "Global title/input fallback size", 
                "smart-default-text-primary-size-sp", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Default secondary text size", "Global subtitle/preview fallback size", 
                "smart-default-text-secondary-size-sp", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Default text style", "Global fallback normal, bold or italic appearance", 
                "smart-default-text-font-style", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Default text colour", "Global fallback colour for launcher text",
                "smart-default-text-color", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Default text shadow", "Enable or disable the global fallback text shadow",
                "smart-default-text-shadow", "ui-holder", Destination.STANDARD, null);

        dynamic(out, "Highlight text style", "Style for letters matching the typed query",
                "smart-highlight-style", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Highlight text size", "Size of matching highlighted query text",
                "smart-highlight-size-percent", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Highlight text colour", "Colour for matching highlighted query text",
                "smart-highlight-color", "ui-holder", Destination.STANDARD, null);

        dynamic(out, "Search title text size", "Size of primary search-result titles",
                "smart-search-title-size-sp", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Search title font style", "Font and style of primary search-result titles",
                "smart-search-title-font", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Search title text color", "Colour of primary search-result titles",
                "smart-search-title-color", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Search title contrast", "Contrast of primary search-result titles",
                "smart-search-title-contrast", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Search body/subtitle size", "Size of secondary search-result text",
                "smart-search-body-size-sp", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Search body font style", "Font and style of secondary search-result text",
                "smart-search-body-font", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Search body text color", "Colour of secondary search-result text",
                "smart-search-body-color", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "Search body contrast", "Contrast of secondary search-result text",
                "smart-search-body-contrast", "ui-holder", Destination.STANDARD, null);

        dynamic(out, "History timestamp text size", "Size of History time, usage and count metadata",
                "smart-history-meta-size-sp", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "History timestamp font style", "Font and style of History metadata",
                "smart-history-meta-font", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "History timestamp text color", "Colour of History metadata",
                "smart-history-meta-color", "ui-holder", Destination.STANDARD, null);
        dynamic(out, "History timestamp contrast", "Contrast of History metadata",
                "smart-history-meta-contrast", "ui-holder", Destination.STANDARD, null);

        dynamic(out, "App history layout", "Vertical list, Vertical Cards and 3D wheel",
                "smart-history-layout", "history_category", Destination.STANDARD, null);
        dynamic(out, "History layout sizing", "Resize history cards, icons, names, rows and spacing", 
                "smart-history-sizing-category", "history_category", Destination.STANDARD, null);
        dynamic(out, "Smart notifications & history", "Timeline, actions, persistent history and notification search", 
                "notifications", null, Destination.SMART_SECTION, "notifications");
        dynamic(out, "Smart wallpaper & blur", "Smart Focus blur, icon tracking, blur strength and performance", 
                "wallpaper", null, Destination.SMART_SECTION, "wallpaper");
        dynamic(out, "Flexible workspace", "Workspace windows, layout and pane behaviour", 
                "workspace", null, Destination.SMART_SECTION, "workspace");
        dynamic(out, "Smart animations & transitions", "Scrolling, windows, popups and animation speed", 
                "animations", null, Destination.SMART_SECTION, "animations");
        dynamic(out, "Frozen apps & app state", "Disabled app launching and background state refresh", 
                "frozen", null, Destination.SMART_SECTION, "frozen");

        dynamic(out, "Semantic search", "On-device embeddings and similarity settings", 
                "semantic-search-enabled", "search-providers", Destination.STANDARD, null);
        dynamic(out, "HNSW fast semantic retrieval", "Nearest-neighbour semantic search without scanning every record",
                SemanticHnswIndex.PREF_HNSW_ENABLED, "search-providers", Destination.STANDARD, null);
        dynamic(out, "HNSW search depth", "Balance semantic lookup speed and recall",
                SemanticHnswIndex.PREF_HNSW_EF_SEARCH, "search-providers", Destination.STANDARD, null);
        dynamic(out, "Use app-store descriptions for semantic search", "Include cached installation-source app descriptions in semantic vectors",
                AppSourceMetadataUpdater.PREF_USE_SOURCE_DESCRIPTIONS, "search-providers", Destination.STANDARD, null);
        dynamic(out, "App source data", "Cached Play Store, F-Droid, Aptoide and catalog descriptions",
                "semantic-app-source-status", "search-providers", Destination.STANDARD, null);
        dynamic(out, "Update all apps source data", "Refresh installed app descriptions from their source catalogs",
                "semantic-update-all-app-source-data", "search-providers", Destination.STANDARD, null);
        dynamic(out, "Semantic index status", "Indexed vectors, dimensions and HNSW timing",
                "semantic-hnsw-status", "search-providers", Destination.STANDARD, null);
        dynamic(out, "Rebuild semantic HNSW index", "Recompute semantic vectors and graph",
                "semantic-hnsw-rebuild", "search-providers", Destination.STANDARD, null);
        dynamic(out, "Embedding model", "Choose the semantic embedding model", 
                "semantic-model", "search-providers", Destination.STANDARD, null);
        dynamic(out, "Embedding dimensions", "Semantic vector dimensions", 
                "semantic-embedding-dimensions", "search-providers", Destination.STANDARD, null);
        dynamic(out, "Semantic similarity threshold", "Broad, balanced or strict semantic matching", 
                "semantic-threshold", "search-providers", Destination.STANDARD, null);

        out.add(new Entry("Battery monitor", "Battery, charging and power monitoring settings",
                null, null, Destination.BATTERY_MONITOR, null, false));
        out.add(new Entry("Indexing settings", "Search indexing and index maintenance settings",
                null, null, Destination.INDEXING_SETTINGS, null, false));
        out.add(new Entry("App usage timeline", "View launcher and phone app usage history",
                null, null, Destination.APP_USAGE, null, false));
    }

    private static void dynamic(List<Entry> out, String title, String summary, String key,
                                String rootKey, Destination destination, String section) {
        out.add(new Entry(title, summary, key, rootKey, destination, section, false));
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static final class ScreenFrame {
        final String key;
        final int depth;

        ScreenFrame(String key, int depth) {
            this.key = key;
            this.depth = depth;
        }
    }
}
