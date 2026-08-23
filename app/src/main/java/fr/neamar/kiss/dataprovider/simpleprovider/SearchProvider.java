package fr.neamar.kiss.dataprovider.simpleprovider;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.telephony.PhoneNumberUtils;
import android.webkit.URLUtil;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import fr.neamar.kiss.R;
import fr.neamar.kiss.index.CommunicationIndexStore;
import fr.neamar.kiss.index.CommunicationIndexer;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.SearchPojo;
import fr.neamar.kiss.pojo.SearchPojoType;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.URIUtils;
import fr.neamar.kiss.utils.URLUtils;

public class SearchProvider extends SimpleProvider<Pojo> {
    private final SharedPreferences prefs;

    public static Set<String> getDefaultSearchProviders(Context context) {
        String[] defaultSearchProviders = context.getResources().getStringArray(R.array.defaultSearchProviders);
        return new HashSet<>(Arrays.asList(defaultSearchProviders));
    }

    @NonNull
    public static Set<String> getAvailableSearchProviders(Context context, SharedPreferences prefs) {
        return new TreeSet<>(prefs.getStringSet("available-search-providers", SearchProvider.getDefaultSearchProviders(context)));
    }

    @NonNull
    public static Set<String> getSelectedSearchProviders(SharedPreferences prefs) {
        return new TreeSet<>(prefs.getStringSet("selected-search-provider-names", new TreeSet<>(Collections.singletonList("Google"))));
    }

    @Nullable
    public static String getDefaultSearchProvider(SharedPreferences prefs) {
        return prefs.getString("default-search-provider", "Google");
    }

    private final List<SearchPojo> searchProviders = new ArrayList<>();
    private final Context context;

    public SearchProvider(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
        CommunicationIndexer.ensureDefaults(this.context);
        reload();
    }

    @Override
    public void reload() {
        searchProviders.clear();
        Set<String> selectedProviders = getSelectedSearchProviders(prefs);
        Set<String> availableProviders = getAvailableSearchProviders(context, prefs);
        String defaultSearchEngine = getDefaultSearchProvider(prefs);

        assert defaultSearchEngine != null;
        for (String searchProvider : selectedProviders) {
            String url = getProviderUrl(availableProviders, searchProvider);
            SearchPojo pojo = new SearchPojo("", url, SearchPojoType.SEARCH_QUERY);
            pojo.relevance = -500;
            if (defaultSearchEngine.equals(searchProvider)) pojo.relevance += 1;
            pojo.setName(searchProvider, false);
            if (pojo.url != null) searchProviders.add(pojo);
        }
    }

    @Override
    public void requestResults(String s, Searcher searcher) {
        searcher.addResults(getResults(s));
    }

    @Override
    public boolean mayFindById(String id) {
        return id != null && id.startsWith(CommunicationIndexStore.ID_PREFIX);
    }

    @Override
    public Pojo findById(String id) {
        if (!mayFindById(id)) return null;
        String raw = id.substring(CommunicationIndexStore.ID_PREFIX.length());
        try (CommunicationIndexStore store = new CommunicationIndexStore(context)) {
            int slash = raw.indexOf('/');
            if (slash > 0 && slash < raw.length() - 1) {
                String source = raw.substring(0, slash);
                String sourceId = raw.substring(slash + 1);
                return store.findBySourceId(source, sourceId);
            }
            try {
                return store.find(Long.parseLong(raw));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private List<Pojo> getResults(String query) {
        List<Pojo> records = new ArrayList<>();

        addCommunicationIndexResults(query, records);
        addClipboardSuggestions(query, records);

        SearchPojo resolvedAction = getResolvedIntentAction(query);
        if (resolvedAction != null) records.add(resolvedAction);

        SearchPojo naturalLanguageAction = getNaturalLanguageAction(query);
        if (naturalLanguageAction != null) records.add(naturalLanguageAction);

        if (prefs.getBoolean("enable-search", true)) {
            SearchPojo chained = getChainedProviderSearch(query);
            if (chained != null) records.add(chained);

            for (SearchPojo pojo : searchProviders) {
                pojo.query = query;
                records.add(pojo);
            }
        }

        if (URLUtils.matchesUrlPattern(query) && URLUtil.isValidUrl(query)) {
            records.add(createUrlQuerySearchPojo(query));
        } else if (URIUtils.isValidUri(query, context).isValid()) {
            SearchPojo pojo = new SearchPojo("search://uri-access", query, "", SearchPojoType.URI_QUERY);
            pojo.relevance = -100;
            pojo.setName(query, false);
            records.add(pojo);
        } else if (URLUtils.matchesUrlPattern(query)) {
            String guessedUrl = URLUtil.guessUrl(query);
            if (URLUtil.isValidUrl(guessedUrl)) records.add(createUrlQuerySearchPojo(guessedUrl));
        }
        return records;
    }

    private void addCommunicationIndexResults(String query, List<Pojo> records) {
        if (!prefs.getBoolean(CommunicationIndexer.PREF_ENABLED, true)) return;
        if (query == null || query.trim().length() < 2) return;
        if (CommunicationIndexer.needsRefresh(context)) {
            android.os.AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> CommunicationIndexer.rebuild(context));
        }
        int limit = Math.max(5, Math.min(200, prefs.getInt(CommunicationIndexer.PREF_LIMIT, 40)));
        try (CommunicationIndexStore store = new CommunicationIndexStore(context)) {
            // Keep communication records as CommunicationPojo. Converting them to SearchPojo made
            // Truecaller items URL results ("Visit") and could route them to a browser instead of
            // CommunicationResult's app-aware launch behavior.
            records.addAll(store.search(query, limit));
        }
    }

    private void addClipboardSuggestions(String query, List<Pojo> records) {
        String command = query.trim().toLowerCase(Locale.ROOT);
        if (!command.equals("clip") && !command.equals("clipboard") && !command.equals("paste")) return;

        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return;

        ClipData data = clipboard.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) return;

        CharSequence value = data.getItemAt(0).coerceToText(context);
        if (value == null) return;
        String text = value.toString().trim();
        if (text.isEmpty()) return;

        if (URLUtils.matchesUrlPattern(text)) {
            String url = URLUtil.isValidUrl(text) ? text : URLUtil.guessUrl(text);
            if (URLUtil.isValidUrl(url)) {
                SearchPojo pojo = createUrlQuerySearchPojo(url);
                pojo.relevance = 500;
                pojo.setName("Open clipboard URL", false);
                records.add(pojo);
                return;
            }
        }

        String normalizedNumber = PhoneNumberUtils.normalizeNumber(text);
        if (!normalizedNumber.isEmpty() && PhoneNumberUtils.isGlobalPhoneNumber(normalizedNumber)) {
            SearchPojo call = createResolvedUriAction("clipboard-call", "Call clipboard number",
                    Uri.fromParts("tel", normalizedNumber, null), Intent.ACTION_DIAL, 500);
            if (call != null) records.add(call);

            SearchPojo sms = createResolvedUriAction("clipboard-sms", "Message clipboard number",
                    Uri.fromParts("smsto", normalizedNumber, null), Intent.ACTION_SENDTO, 490);
            if (sms != null) records.add(sms);
            return;
        }

        SearchPojo map = createResolvedUriAction("clipboard-map", "Map clipboard text",
                Uri.parse("geo:0,0?q=" + Uri.encode(text)), Intent.ACTION_VIEW, 470);
        if (map != null) records.add(map);

        SearchPojo defaultSearch = getDefaultSearch(text, context, prefs);
        if (defaultSearch != null) {
            defaultSearch.relevance = 450;
            defaultSearch.setName("Search clipboard text", false);
            records.add(defaultSearch);
        }
    }

    @Nullable
    private SearchPojo getResolvedIntentAction(String query) {
        String trimmed = query.trim();
        int separator = trimmed.indexOf(' ');
        if (separator <= 0 || separator == trimmed.length() - 1) return null;

        String verb = trimmed.substring(0, separator).toLowerCase(Locale.ROOT);
        String argument = trimmed.substring(separator + 1).trim();
        switch (verb) {
            case "call":
            case "dial": {
                String number = PhoneNumberUtils.normalizeNumber(argument);
                if (number.isEmpty() || !PhoneNumberUtils.isGlobalPhoneNumber(number)) return null;
                return createResolvedUriAction("dial", "Call " + argument,
                        Uri.fromParts("tel", number, null), Intent.ACTION_DIAL, 420);
            }
            case "sms":
            case "text": {
                String number = PhoneNumberUtils.normalizeNumber(argument);
                if (number.isEmpty() || !PhoneNumberUtils.isGlobalPhoneNumber(number)) return null;
                return createResolvedUriAction("sms", "Message " + argument,
                        Uri.fromParts("smsto", number, null), Intent.ACTION_SENDTO, 420);
            }
            case "map":
            case "maps":
                return createResolvedUriAction("map", "Map " + argument,
                        Uri.parse("geo:0,0?q=" + Uri.encode(argument)), Intent.ACTION_VIEW, 420);
            default:
                return null;
        }
    }

    @Nullable
    private SearchPojo getNaturalLanguageAction(String query) {
        String trimmed = query.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        String argument = stripPrefix(lower, trimmed, "navigate to ", "directions to ", "take me to ");
        if (argument != null) {
            return createResolvedUriAction("navigate", "Navigate to " + argument,
                    Uri.parse("geo:0,0?q=" + Uri.encode(argument)), Intent.ACTION_VIEW, 465);
        }

        argument = stripPrefix(lower, trimmed, "search wol for ", "find on wol ");
        if (argument != null) return createChainedSearch("WOL", "https://wol.jw.org/en/wol/s/r1/lp-e?q=%s", argument, 460);

        argument = stripPrefix(lower, trimmed, "search jw for ", "search jw.org for ");
        if (argument != null) return createChainedSearch("JW.org", "https://www.jw.org/en/search/?q=%s", argument, 460);

        argument = stripPrefix(lower, trimmed, "search youtube for ", "find on youtube ");
        if (argument != null) return createChainedSearch("YouTube", "https://www.youtube.com/results?search_query=%s", argument, 455);

        argument = stripPrefix(lower, trimmed, "search web for ", "web search for ");
        if (argument != null) {
            SearchPojo pojo = getDefaultSearch(argument, context, prefs);
            if (pojo != null) {
                pojo.relevance = 450;
                pojo.setName("Search web for " + argument, false);
            }
            return pojo;
        }

        String suffix = " on play store";
        if (lower.startsWith("find ") && lower.endsWith(suffix) && trimmed.length() > 5 + suffix.length()) {
            String app = trimmed.substring(5, trimmed.length() - suffix.length()).trim();
            return createChainedSearch("Google Play Store", "https://play.google.com/store/search?q=%s", app, 455);
        }
        return null;
    }

    @Nullable
    private String stripPrefix(String lower, String original, String... prefixes) {
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix) && original.length() > prefix.length()) {
                String value = original.substring(prefix.length()).trim();
                if (!value.isEmpty()) return value;
            }
        }
        return null;
    }

    @Nullable
    private SearchPojo createResolvedUriAction(String id, String name, Uri uri, String action, int relevance) {
        Intent intent = new Intent(action, uri);
        if (context.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) return null;

        SearchPojo pojo = new SearchPojo("search://action/" + id, uri.toString(), "", SearchPojoType.URI_QUERY);
        pojo.relevance = relevance;
        pojo.setName(name, false);
        return pojo;
    }

    @Nullable
    private SearchPojo getChainedProviderSearch(String query) {
        String trimmed = query.trim();
        int separator = trimmed.indexOf(' ');
        if (separator <= 0 || separator == trimmed.length() - 1) return null;

        String prefix = trimmed.substring(0, separator).toLowerCase(Locale.ROOT);
        String providerQuery = trimmed.substring(separator + 1).trim();
        String providerName;
        String url;
        switch (prefix) {
            case "wol":
                providerName = "WOL";
                url = "https://wol.jw.org/en/wol/s/r1/lp-e?q=%s";
                break;
            case "jw":
                providerName = "JW.org";
                url = "https://www.jw.org/en/search/?q=%s";
                break;
            case "yt":
            case "youtube":
                providerName = "YouTube";
                url = "https://www.youtube.com/results?search_query=%s";
                break;
            case "maps":
                providerName = "Google Maps";
                url = "https://www.google.com/maps/search/?api=1&query=%s";
                break;
            case "play":
                providerName = "Google Play Store";
                url = "https://play.google.com/store/search?q=%s";
                break;
            default:
                return getCustomChainedProvider(prefix, providerQuery);
        }
        return createChainedSearch(providerName, url, providerQuery);
    }

    @Nullable
    private SearchPojo getCustomChainedProvider(String prefix, String providerQuery) {
        Set<String> available = getAvailableSearchProviders(context, prefs);
        for (String entry : available) {
            int separator = entry.indexOf('|');
            if (separator <= 0) continue;
            String name = entry.substring(0, separator);
            String normalizedName = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (normalizedName.equals(prefix)) {
                return createChainedSearch(name, entry.substring(separator + 1), providerQuery);
            }
        }
        return null;
    }

    private SearchPojo createChainedSearch(String name, String url, String providerQuery) {
        return createChainedSearch(name, url, providerQuery, 400);
    }

    private SearchPojo createChainedSearch(String name, String url, String providerQuery, int relevance) {
        SearchPojo pojo = new SearchPojo("search://chain/" + name.toLowerCase(Locale.ROOT), providerQuery, url, SearchPojoType.SEARCH_QUERY);
        pojo.relevance = relevance;
        pojo.setName(name, false);
        return pojo;
    }

    private SearchPojo createUrlQuerySearchPojo(String url) {
        url = url.replace("http://", "https://");
        SearchPojo pojo = new SearchPojo("search://url-access", "", url, SearchPojoType.URL_QUERY);
        pojo.relevance = 50;
        pojo.setName(url, false);
        return pojo;
    }

    @Nullable
    private static String getProviderUrl(Set<String> searchProviders, String searchProviderName) {
        for (String nameAndUrl : searchProviders) {
            if (nameAndUrl.contains(searchProviderName + "|")) {
                String[] arrayNameAndUrl = nameAndUrl.split("\\|", 2);
                if (arrayNameAndUrl.length == 2) return arrayNameAndUrl[1];
            }
        }
        return null;
    }

    @Nullable
    public static SearchPojo getDefaultSearch(final String query, final Context context,
            @Nullable SharedPreferences pref) {
        pref = pref != null ? pref : PreferenceManager.getDefaultSharedPreferences(context);
        String defaultSearchEngine = getDefaultSearchProvider(pref);
        Set<String> availableProviders = getAvailableSearchProviders(context, pref);
        String url = getProviderUrl(availableProviders, defaultSearchEngine);
        return url != null ? new SearchPojo(query, url, SearchPojoType.SEARCH_QUERY) : null;
    }
}
