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
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.SearchPojo;
import fr.neamar.kiss.pojo.SearchPojoType;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.URIUtils;
import fr.neamar.kiss.utils.URLUtils;

public class SearchProvider extends SimpleProvider<SearchPojo> {
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

    private List<Pojo> getResults(String query) {
        List<Pojo> records = new ArrayList<>();

        addClipboardSuggestions(query, records);

        SearchPojo resolvedAction = getResolvedIntentAction(query);
        if (resolvedAction != null) records.add(resolvedAction);

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

    /**
     * Explicit clipboard commands keep clipboard access user-driven rather than reading it on every
     * keystroke. Type "clip", "clipboard" or "paste" to surface useful actions for the current item.
     */
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

    /** Searchable, resolve-checked verbs for common external actions. */
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
    private SearchPojo createResolvedUriAction(String id, String name, Uri uri, String action, int relevance) {
        Intent intent = new Intent(action, uri);
        if (context.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) return null;

        SearchPojo pojo = new SearchPojo("search://action/" + id, uri.toString(), "", SearchPojoType.URI_QUERY);
        pojo.relevance = relevance;
        pojo.setName(name, false);
        return pojo;
    }

    /** Prefix chaining: "wol threefold", "yt music", "maps pretoria", etc. */
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
        SearchPojo pojo = new SearchPojo("search://chain/" + name.toLowerCase(Locale.ROOT), providerQuery, url, SearchPojoType.SEARCH_QUERY);
        pojo.relevance = 400;
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
