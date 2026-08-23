package fr.neamar.kiss.index;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.SystemClock;
import android.provider.CallLog;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.SmartStateStore;

public final class CommunicationIndexer {
    public static final String PREF_ENABLED = "smart-index-communications-enabled";
    public static final String PREF_CALLS = "smart-index-calls";
    public static final String PREF_SMS = "smart-index-sms";
    public static final String PREF_TRUECALLER = "smart-index-truecaller";
    public static final String PREF_DAYS = "smart-index-retention-days";
    public static final String PREF_LIMIT = "smart-index-search-limit";
    public static final String PREF_AUTO = "smart-index-auto-refresh";
    public static final String PREF_LAST = "smart-index-last-refresh";
    public static final String PREF_CALL_HISTORY_LAST_SYNCED_TIME = "smart-index-call-history-last-synced-time";
    public static final String PREF_TRUECALLER_NAME_ENRICHMENT_VERSION = "smart-index-truecaller-name-enrichment-version";

    private static final String TRUECALLER_PACKAGE = "com.truecaller";
    private static final int INITIAL_CALL_HISTORY_ROWS = 20;
    private static final int TRUECALLER_NAME_ENRICHMENT_VERSION = 2;
    private static final long NEWEST_CALL_CHECK_THROTTLE_MS = 5_000L;
    private static final long TRUECALLER_TIME_MATCH_WINDOW_MS = 90_000L;
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\+?[0-9][0-9\\s().-]{5,}[0-9]");
    private static long lastNewestCallCheckElapsed;
    private static long cachedNewestCallTime;

    private static final class CallRow {
        final String sourceId;
        final String number;
        final String cachedName;
        final long when;
        final int type;
        final long duration;

        CallRow(String sourceId, String number, String cachedName, long when, int type, long duration) {
            this.sourceId = sourceId;
            this.number = number == null ? "" : number;
            this.cachedName = cachedName == null ? "" : cachedName;
            this.when = when;
            this.type = type;
            this.duration = duration;
        }
    }

    private static final class TruecallerNameHint {
        final String name;
        final String number;
        final long when;

        TruecallerNameHint(String name, String number, long when) {
            this.name = name == null ? "" : name;
            this.number = number == null ? "" : number;
            this.when = when;
        }
    }

    private CommunicationIndexer() { }

    public static void ensureDefaults(Context context) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        if (!p.contains(PREF_ENABLED)) {
            p.edit().putBoolean(PREF_ENABLED, true)
                    .putBoolean(PREF_CALLS, true)
                    .putBoolean(PREF_SMS, true)
                    .putBoolean(PREF_TRUECALLER, true)
                    .putBoolean(PREF_AUTO, true)
                    .putInt(PREF_DAYS, 365)
                    .putInt(PREF_LIMIT, 40).apply();
        }
    }

    public static boolean needsRefresh(Context context) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        if (!p.getBoolean(PREF_AUTO, true)) return false;

        if (p.getBoolean(PREF_CALLS, true)
                && p.getBoolean(PREF_TRUECALLER, true)
                && p.getInt(PREF_TRUECALLER_NAME_ENRICHMENT_VERSION, 0)
                < TRUECALLER_NAME_ENRICHMENT_VERSION) {
            return true;
        }

        if (p.getBoolean(PREF_CALLS, true)
                && !p.contains(PREF_CALL_HISTORY_LAST_SYNCED_TIME)) {
            return true;
        }

        long lastRefresh = p.getLong(PREF_LAST, 0L);
        if (p.getBoolean(PREF_CALLS, true) && newestCallTime(context) > lastRefresh) return true;
        return System.currentTimeMillis() - lastRefresh > 15 * 60_000L;
    }

    private static synchronized long newestCallTime(Context context) {
        long elapsed = SystemClock.elapsedRealtime();
        if (elapsed - lastNewestCallCheckElapsed < NEWEST_CALL_CHECK_THROTTLE_MS) {
            return cachedNewestCallTime;
        }
        lastNewestCallCheckElapsed = elapsed;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            cachedNewestCallTime = 0L;
            return 0L;
        }
        try (Cursor c = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.DATE},
                null, null,
                CallLog.Calls.DATE + " DESC")) {
            cachedNewestCallTime = c != null && c.moveToFirst() ? c.getLong(0) : 0L;
            return cachedNewestCallTime;
        } catch (RuntimeException ignored) {
            cachedNewestCallTime = 0L;
            return 0L;
        }
    }

    public static synchronized void rebuild(Context context) {
        ensureDefaults(context);
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        if (!p.getBoolean(PREF_ENABLED, true)) return;

        CommunicationIndexStore store = new CommunicationIndexStore(context);
        try {
            store.clear();
            long cutoff = cutoff(p);
            List<NotificationHistoryRecord> truecallerRecords = p.getBoolean(PREF_TRUECALLER, true)
                    ? loadTruecallerNotifications(context, cutoff)
                    : Collections.emptyList();

            if (p.getBoolean(PREF_CALLS, true)) {
                indexCalls(context, store, cutoff, p, truecallerRecords);
            }
            if (p.getBoolean(PREF_SMS, true)) indexSms(context, store, cutoff);
            if (p.getBoolean(PREF_TRUECALLER, true)) {
                indexTruecallerNotifications(store, truecallerRecords);
            }
            store.trimOlderThan(cutoff);
            p.edit()
                    .putLong(PREF_LAST, System.currentTimeMillis())
                    .putInt(PREF_TRUECALLER_NAME_ENRICHMENT_VERSION,
                            TRUECALLER_NAME_ENRICHMENT_VERSION)
                    .apply();
        } finally {
            store.close();
        }
    }

    private static long cutoff(SharedPreferences p) {
        int days = Math.max(1, Math.min(3650, p.getInt(PREF_DAYS, 365)));
        return System.currentTimeMillis() - days * 86_400_000L;
    }

    private static List<NotificationHistoryRecord> loadTruecallerNotifications(Context context, long cutoff) {
        List<NotificationHistoryRecord> all = SmartStateStore.queryNotifications(
                context, TRUECALLER_PACKAGE, null, 10000);
        if (all.isEmpty()) return all;

        List<NotificationHistoryRecord> filtered = new ArrayList<>();
        for (NotificationHistoryRecord record : all) {
            if (record != null && record.postTime >= cutoff) filtered.add(record);
        }
        return filtered;
    }

    private static void indexCalls(Context context, CommunicationIndexStore store, long cutoff,
                                   SharedPreferences prefs,
                                   List<NotificationHistoryRecord> truecallerRecords) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) return;

        long lastSyncedTime = prefs.getLong(PREF_CALL_HISTORY_LAST_SYNCED_TIME, 0L);
        long newestSeenTime = lastSyncedTime;
        List<String> newHistoryIds = new ArrayList<>();
        List<CallRow> calls = new ArrayList<>();

        String[] projection = {CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION};
        try (Cursor c = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, projection,
                CallLog.Calls.DATE + ">?", new String[]{Long.toString(cutoff)},
                CallLog.Calls.DATE + " DESC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                CallRow row = new CallRow(
                        Long.toString(c.getLong(0)), c.getString(1), c.getString(2),
                        c.getLong(3), c.getInt(4), c.getLong(5));
                calls.add(row);
                newestSeenTime = Math.max(newestSeenTime, row.when);
                if (lastSyncedTime == 0L || row.when > lastSyncedTime) {
                    newHistoryIds.add(CommunicationIndexStore.stableId("call", row.sourceId));
                }
            }
        } catch (RuntimeException ignored) {
            return;
        }

        List<String> callNumbers = new ArrayList<>(calls.size());
        for (CallRow row : calls) {
            if (!TextUtils.isEmpty(row.number)) callNumbers.add(row.number);
        }
        Map<String, String> readableTruecallerNames =
                CallerIdentityResolver.loadReadableTruecallerNames(context, callNumbers);
        Map<String, String> contactNameCache = new HashMap<>();
        List<TruecallerNameHint> notificationHints = buildTruecallerHints(truecallerRecords);

        for (CallRow row : calls) {
            String displayName = "";

            // 1) The Android CallLog's own name is authoritative when it is a real identity.
            if (hasUsefulCachedName(row.cachedName, row.number)) {
                displayName = row.cachedName.trim();
            }

            // 2) If the installed Truecaller build exposes an exported/readable provider with an
            // exact number/name row, use it. No private authority or schema is hard-coded.
            if (TextUtils.isEmpty(displayName)) {
                displayName = CallerIdentityResolver.getTruecallerProviderName(
                        row.number, readableTruecallerNames);
            }

            // 3) PhoneLookup can expose saved contacts and any caller identities synchronized into
            // Android's shared contacts database. Cache per number so repeated calls stay cheap.
            if (TextUtils.isEmpty(displayName) && !TextUtils.isEmpty(row.number)) {
                String cachedContact = contactNameCache.get(row.number);
                if (cachedContact == null) {
                    cachedContact = CallerIdentityResolver.getAndroidContactName(context, row.number);
                    contactNameCache.put(row.number, cachedContact);
                }
                displayName = cachedContact;
            }

            // 4/5) Finally use captured Truecaller notification evidence: exact number first, then
            // a unique unambiguous time match. Never infer across multiple possible calls.
            if (TextUtils.isEmpty(displayName)) {
                displayName = resolveTruecallerName(row, calls, notificationHints);
            }

            if (TextUtils.isEmpty(displayName)) displayName = row.number;

            String body = callType(row.type) + " call · " + row.duration + " sec";
            store.put("call", row.sourceId, TRUECALLER_PACKAGE, row.number,
                    displayName, body, row.when, "");
        }

        if (lastSyncedTime == 0L && newHistoryIds.size() > INITIAL_CALL_HISTORY_ROWS) {
            newHistoryIds = new ArrayList<>(newHistoryIds.subList(0, INITIAL_CALL_HISTORY_ROWS));
        }

        if (!prefs.getBoolean("freeze-history", false)) {
            Collections.reverse(newHistoryIds);
            for (String historyId : newHistoryIds) {
                DBHelper.insertHistory(context, "call-log", historyId);
            }
        }

        if (newestSeenTime > lastSyncedTime) {
            prefs.edit().putLong(PREF_CALL_HISTORY_LAST_SYNCED_TIME, newestSeenTime).apply();
        } else if (!prefs.contains(PREF_CALL_HISTORY_LAST_SYNCED_TIME)) {
            prefs.edit().putLong(PREF_CALL_HISTORY_LAST_SYNCED_TIME, 0L).apply();
        }
    }

    private static boolean hasUsefulCachedName(String cachedName, String number) {
        if (!CallerIdentityResolver.isUsableName(cachedName)) return false;
        String clean = cachedName.trim();
        return TextUtils.isEmpty(number) || !phoneNumbersMatch(clean, number);
    }

    private static List<TruecallerNameHint> buildTruecallerHints(
            List<NotificationHistoryRecord> records) {
        if (records == null || records.isEmpty()) return Collections.emptyList();

        List<TruecallerNameHint> hints = new ArrayList<>();
        for (NotificationHistoryRecord record : records) {
            if (record == null) continue;
            String combined = safe(record.title) + "\n" + safe(record.text);
            String number = extractPhoneNumber(combined);
            String name = extractTruecallerName(record.title, record.text, number);
            if (!TextUtils.isEmpty(name)) {
                hints.add(new TruecallerNameHint(name, number, record.postTime));
            }
        }
        return hints;
    }

    private static String resolveTruecallerName(CallRow row,
                                                List<CallRow> allCalls,
                                                List<TruecallerNameHint> hints) {
        if (TextUtils.isEmpty(row.number) || hints.isEmpty()) return "";

        TruecallerNameHint bestExact = null;
        long bestExactDistance = Long.MAX_VALUE;
        for (TruecallerNameHint hint : hints) {
            if (TextUtils.isEmpty(hint.number)) continue;
            if (!phoneNumbersMatch(row.number, hint.number)) continue;
            long distance = Math.abs(row.when - hint.when);
            if (distance < bestExactDistance) {
                bestExactDistance = distance;
                bestExact = hint;
            }
        }
        if (bestExact != null) return bestExact.name;

        String uniqueName = "";
        for (TruecallerNameHint hint : hints) {
            if (!TextUtils.isEmpty(hint.number)) continue;
            long distance = Math.abs(row.when - hint.when);
            if (distance > TRUECALLER_TIME_MATCH_WINDOW_MS) continue;
            if (!isUniqueCallNearHint(row, allCalls, hint.when)) continue;

            if (TextUtils.isEmpty(uniqueName)) {
                uniqueName = hint.name;
            } else if (!uniqueName.equalsIgnoreCase(hint.name)) {
                return "";
            }
        }
        return uniqueName;
    }

    private static boolean isUniqueCallNearHint(CallRow target, List<CallRow> allCalls, long hintTime) {
        int matches = 0;
        boolean targetMatched = false;
        for (CallRow candidate : allCalls) {
            if (Math.abs(candidate.when - hintTime) <= TRUECALLER_TIME_MATCH_WINDOW_MS) {
                matches++;
                if (candidate == target) targetMatched = true;
                if (matches > 1) return false;
            }
        }
        return targetMatched && matches == 1;
    }

    private static boolean phoneNumbersMatch(String first, String second) {
        if (TextUtils.isEmpty(first) || TextUtils.isEmpty(second)) return false;
        String a = PhoneNumberUtils.normalizeNumber(first);
        String b = PhoneNumberUtils.normalizeNumber(second);
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) return false;
        if (a.equals(b)) return true;
        try {
            return PhoneNumberUtils.compare(first, second);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String extractPhoneNumber(String value) {
        if (TextUtils.isEmpty(value)) return "";
        Matcher matcher = PHONE_PATTERN.matcher(value);
        while (matcher.find()) {
            String candidate = matcher.group();
            String normalized = PhoneNumberUtils.normalizeNumber(candidate);
            if (!TextUtils.isEmpty(normalized) && normalized.replace("+", "").length() >= 7) {
                return candidate.trim();
            }
        }
        return "";
    }

    private static String extractTruecallerName(String title, String text, String number) {
        String fromTitle = cleanTruecallerName(title, number);
        if (!TextUtils.isEmpty(fromTitle)) return fromTitle;
        return cleanTruecallerName(text, number);
    }

    private static String cleanTruecallerName(String value, String number) {
        if (TextUtils.isEmpty(value)) return "";
        String clean = value.replace('\n', ' ').trim();
        if (clean.isEmpty()) return "";

        clean = clean.replaceFirst("(?i)^truecaller\\s*[:\\-·]?\\s*", "");
        clean = clean.replaceFirst("(?i)^call\\s+from\\s+", "");
        clean = clean.replaceFirst("(?i)^calling(?:\\.\\.)?\\s*[:\\-·]?\\s*", "");
        clean = clean.replaceFirst("(?i)\\s+(?:is\\s+)?calling(?:\\.\\.\\.)?.*$", "");
        clean = clean.replaceFirst("(?i)\\s+(?:called you|missed call|incoming call|outgoing call|blocked call|rejected call).*$", "");
        clean = clean.replaceFirst("(?i)\\s*[·|\\-]\\s*\\+?[0-9][0-9\\s().-]{5,}[0-9].*$", "");
        clean = clean.trim();

        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() > 1) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        if (!CallerIdentityResolver.isUsableName(clean)) return "";
        if (!TextUtils.isEmpty(number) && phoneNumbersMatch(clean, number)) return "";
        if (!TextUtils.isEmpty(extractPhoneNumber(clean))) return "";
        return clean;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String callType(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE: return "Incoming";
            case CallLog.Calls.OUTGOING_TYPE: return "Outgoing";
            case CallLog.Calls.MISSED_TYPE: return "Missed";
            case CallLog.Calls.REJECTED_TYPE: return "Rejected";
            case CallLog.Calls.BLOCKED_TYPE: return "Blocked";
            default: return "Phone";
        }
    }

    private static void indexSms(Context context, CommunicationIndexStore store, long cutoff) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) return;
        String[] projection = {Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, Telephony.Sms.TYPE};
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, projection,
                Telephony.Sms.DATE + ">?", new String[]{Long.toString(cutoff)},
                Telephony.Sms.DATE + " DESC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                String address = c.getString(1);
                String body = c.getString(2);
                int type = c.getInt(4);
                String label = type == Telephony.Sms.MESSAGE_TYPE_SENT ? "Sent SMS" : "SMS";
                store.put("sms", Long.toString(c.getLong(0)), TRUECALLER_PACKAGE, address, address,
                        label + " · " + (body == null ? "" : body), c.getLong(3), "");
            }
        } catch (RuntimeException ignored) { }
    }

    private static void indexTruecallerNotifications(CommunicationIndexStore store,
                                                     List<NotificationHistoryRecord> records) {
        for (NotificationHistoryRecord record : records) {
            String number = extractPhoneNumber(safe(record.title) + "\n" + safe(record.text));
            String name = extractTruecallerName(record.title, record.text, number);
            String display = !TextUtils.isEmpty(name) ? name
                    : TextUtils.isEmpty(record.title) ? "Truecaller" : record.title.trim();
            String body = record.text == null ? "" : record.text;
            store.put("truecaller", Long.toString(record.dbId), TRUECALLER_PACKAGE, number,
                    display, body, record.postTime, record.notificationId);
        }
    }
}
