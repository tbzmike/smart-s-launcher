package fr.neamar.kiss.dataprovider;

import android.database.ContentObserver;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.loader.LoadContactsPojos;
import fr.neamar.kiss.normalizer.PhoneNormalizer;
import fr.neamar.kiss.normalizer.StringNormalizer;
import fr.neamar.kiss.pojo.ContactData;
import fr.neamar.kiss.pojo.ContactsPojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.MimeTypeUtils;
import fr.neamar.kiss.utils.Permission;
import fr.neamar.kiss.utils.PhoneUtils;
import fr.neamar.kiss.utils.fuzzy.FuzzyFactory;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;
import fr.neamar.kiss.utils.fuzzy.MatchInfo;

public class ContactsProvider extends Provider<ContactsPojo> {
    protected static final String TAG = ContactsProvider.class.getSimpleName();
    private final Map<String, StringNormalizer.Result> socialAliasCache = new ConcurrentHashMap<>();
    private final ContentObserver cObserver = new ContentObserver(null) {

        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, @Nullable Uri uri) {
            onChange(selfChange, uri, 0);
        }

        @Override
        public void onChange(boolean selfChange, @Nullable Uri uri, int flags) {
            onChange(selfChange, Collections.singletonList(uri), flags);
        }

        @Override
        public void onChange(boolean selfChange, @NonNull Collection<Uri> uris, int flags) {
            Log.v(TAG, "Contacts changed, reloading provider: " + uris + ", flags: " + flags);
            reload();
        }
    };

    @Override
    public void reload() {
        socialAliasCache.clear();
        super.reload();
        this.initialize(new LoadContactsPojos(this));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Permission.checkPermission(this, Permission.PERMISSION_READ_CONTACTS)) {
            getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI, false, cObserver);
        } else {
            Permission.askPermission(Permission.PERMISSION_READ_CONTACTS, new Permission.PermissionResultListener() {
                @Override
                public void onGranted() {
                    reload();
                }
            });
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getContentResolver().unregisterContentObserver(cObserver);
    }

    @Override
    public void requestResults(String query, Searcher searcher) {
        StringNormalizer.Result queryNormalized = StringNormalizer.normalizeWithResult(query, false);

        if (queryNormalized.codePoints.length == 0) {
            return;
        }

        FuzzyScore fuzzyScore = FuzzyFactory.createFuzzyScore(this, queryNormalized.codePoints);
        int checked = 0;

        for (ContactsPojo pojo : getPojos()) {
            if ((checked++ & 31) == 0 && searcher.isCancelled()) return;

            MatchInfo matchInfo;
            boolean match = false;

            if (pojo.normalizedName != null) {
                matchInfo = fuzzyScore.match(pojo.normalizedName.codePoints);
                match = pojo.updateMatchingRelevance(matchInfo, match);
            }

            if (pojo.normalizedNameAlternative != null) {
                matchInfo = fuzzyScore.match(pojo.normalizedNameAlternative.codePoints);
                match = pojo.updateMatchingRelevance(matchInfo, match);
            }

            if (pojo.normalizedPhoneticName != null) {
                matchInfo = fuzzyScore.match(pojo.normalizedPhoneticName.codePoints);
                match = pojo.updateMatchingRelevance(matchInfo, match);
            }

            if (pojo.normalizedNickname != null) {
                matchInfo = fuzzyScore.match(pojo.normalizedNickname.codePoints);
                match = pojo.updateMatchingRelevance(matchInfo, match);
            }

            if (!match && queryNormalized.length() > 2 && pojo.normalizedPhone != null) {
                matchInfo = fuzzyScore.match(pojo.normalizedPhone.codePoints);
                match = pojo.updateMatchingRelevance(matchInfo, match);
            }

            ContactData contactData = pojo.getContactData();
            if (!match && queryNormalized.length() > 2 && contactData != null
                    && contactData.getNormalizedIdentifier() != null) {
                matchInfo = fuzzyScore.match(contactData.getNormalizedIdentifier().codePoints);
                match = pojo.updateMatchingRelevance(matchInfo, match);
            }

            // Allow a cached combined alias such as "WhatsApp John" to match both the service and
            // the contact name without rebuilding normalized strings on every keystroke.
            if (contactData != null && MimeTypeUtils.isSocialContactMimeType(contactData.getMimeType())) {
                StringNormalizer.Result socialAlias = getSocialAlias(pojo, contactData);
                if (socialAlias != null) {
                    matchInfo = fuzzyScore.match(socialAlias.codePoints);
                    match = pojo.updateMatchingRelevance(matchInfo, match);
                }
            }

            if (match) {
                if (pojo.starred) {
                    pojo.relevance += 40;
                }

                if (!searcher.addResult(pojo))
                    return;
            }
        }
    }

    @Nullable
    private StringNormalizer.Result getSocialAlias(ContactsPojo pojo, ContactData contactData) {
        String key = pojo.id;
        StringNormalizer.Result cached = socialAliasCache.get(key);
        if (cached != null) return cached;

        String serviceLabel = KissApplication.getMimeTypeCache(this)
                .getLabel(this, contactData.getMimeType());
        String contactName = pojo.getName();
        if (TextUtils.isEmpty(serviceLabel) || TextUtils.isEmpty(contactName)) return null;

        String displayName = contactName;
        String prefix = serviceLabel + " ";
        if (!contactName.regionMatches(true, 0, prefix, 0, prefix.length())) {
            displayName = prefix + contactName;
            pojo.setName(displayName);
        }

        StringNormalizer.Result normalized = StringNormalizer.normalizeWithResult(displayName, false);
        socialAliasCache.put(key, normalized);
        return normalized;
    }

    /**
     * Find a ContactsPojo from a phoneNumber
     * If many contacts match, the one most often contacted will be returned
     *
     * @param phoneNumber phone number to find (will be normalized)
     * @return a ContactsPojo, or null.
     */
    public ContactsPojo findByPhone(String phoneNumber) {
        StringNormalizer.Result simplifiedPhoneNumber = PhoneNormalizer.normalizeWithResult(phoneNumber);

        PhoneUtils phoneUtils = new PhoneUtils(this);
        for (ContactsPojo pojo : getPojos()) {
            if (pojo.normalizedPhone != null && phoneUtils.areSamePhoneNumber(pojo.normalizedPhone, simplifiedPhoneNumber)) {
                return pojo;
            }
        }

        return null;
    }
}
