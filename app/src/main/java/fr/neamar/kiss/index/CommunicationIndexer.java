package fr.neamar.kiss.index;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.SystemClock;
import android.provider.CallLog;
import android.provider.Telephony;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    private static final String TRUECALLER_PACKAGE = "com.truecaller";
    private static final int INITIAL_CALL_HISTORY_ROWS = 20;
    private static final long NEWEST_CALL_CHECK_THROTTLE_MS = 5_000L;
    private static long lastNewestCallCheckElapsed;
    private static long cachedNewestCallTime;

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

    public static void rebuild(Context context) {
        ensureDefaults(context);
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        if (!p.getBoolean(PREF_ENABLED, true)) return;
        CommunicationIndexStore store = new CommunicationIndexStore(context);
        try {
            store.clear();
            long cutoff = cutoff(p);
            if (p.getBoolean(PREF_CALLS, true)) indexCalls(context, store, cutoff, p);
            if (p.getBoolean(PREF_SMS, true)) indexSms(context, store, cutoff);
            if (p.getBoolean(PREF_TRUECALLER, true)) indexTruecallerNotifications(context, store, cutoff);
            store.trimOlderThan(cutoff);
            p.edit().putLong(PREF_LAST, System.currentTimeMillis()).apply();
        } finally {
            store.close();
        }
    }

    private static long cutoff(SharedPreferences p) {
        int days = Math.max(1, Math.min(3650, p.getInt(PREF_DAYS, 365)));
        return System.currentTimeMillis() - days * 86_400_000L;
    }

    private static void indexCalls(Context context, CommunicationIndexStore store, long cutoff,
                                   SharedPreferences prefs) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) return;

        long lastSyncedTime = prefs.getLong(PREF_CALL_HISTORY_LAST_SYNCED_TIME, 0L);
        long newestSeenTime = lastSyncedTime;
        List<String> newHistoryIds = new ArrayList<>();

        String[] projection = {CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION};
        try (Cursor c = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, projection,
                CallLog.Calls.DATE + ">?", new String[]{Long.toString(cutoff)},
                CallLog.Calls.DATE + " DESC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                String sourceId = Long.toString(c.getLong(0));
                String number = c.getString(1);
                String name = c.getString(2);
                long when = c.getLong(3);
                int type = c.getInt(4);
                long duration = c.getLong(5);
                String body = callType(type) + " call · " + duration + " sec";
                store.put("call", sourceId, TRUECALLER_PACKAGE, number, name, body, when, "");

                newestSeenTime = Math.max(newestSeenTime, when);
                if (lastSyncedTime == 0L || when > lastSyncedTime) {
                    newHistoryIds.add(CommunicationIndexStore.stableId("call", sourceId));
                }
            }
        } catch (RuntimeException ignored) {
            return;
        }

        if (lastSyncedTime == 0L && newHistoryIds.size() > INITIAL_CALL_HISTORY_ROWS) {
            newHistoryIds = new ArrayList<>(newHistoryIds.subList(0, INITIAL_CALL_HISTORY_ROWS));
        }

        boolean keepPhoneHistory = prefs.getBoolean("enable-phone-history", false);
        boolean freezeHistory = prefs.getBoolean("freeze-history", false);
        if (keepPhoneHistory && !freezeHistory) {
            // Query order is newest -> oldest. KISS recency is based on history insertion order, so
            // import oldest -> newest and the final visible order matches the real phone call order.
            Collections.reverse(newHistoryIds);
            for (String historyId : newHistoryIds) {
                DBHelper.insertHistory(context, "call-log", historyId);
            }
        }

        // Advance the marker even while history is frozen/disabled. Calls that occurred while the
        // user intentionally disabled/froze phone history should not flood history later.
        if (newestSeenTime > lastSyncedTime) {
            prefs.edit().putLong(PREF_CALL_HISTORY_LAST_SYNCED_TIME, newestSeenTime).apply();
        }
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
                CallLog.Calls.DATE + " DESC")) {
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

    private static void indexTruecallerNotifications(Context context, CommunicationIndexStore store,
                                                     long cutoff) {
        List<NotificationHistoryRecord> records = SmartStateStore.queryNotifications(
                context, TRUECALLER_PACKAGE, null, 10000);
        for (NotificationHistoryRecord r : records) {
            if (r.postTime < cutoff) continue;
            String display = r.title == null || r.title.trim().isEmpty() ? "Truecaller" : r.title;
            String body = r.text == null ? "" : r.text;
            store.put("truecaller", Long.toString(r.dbId), TRUECALLER_PACKAGE, "", display, body,
                    r.postTime, r.notificationId);
        }
    }
}
