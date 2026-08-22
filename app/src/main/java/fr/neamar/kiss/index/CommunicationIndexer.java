package fr.neamar.kiss.index;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;
import android.provider.Telephony;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.List;

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

    private static final String TRUECALLER_PACKAGE = "com.truecaller";

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
        return p.getBoolean(PREF_AUTO, true)
                && System.currentTimeMillis() - p.getLong(PREF_LAST, 0L) > 15 * 60_000L;
    }

    public static void rebuild(Context context) {
        ensureDefaults(context);
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        if (!p.getBoolean(PREF_ENABLED, true)) return;
        CommunicationIndexStore store = new CommunicationIndexStore(context);
        try {
            store.clear();
            long cutoff = cutoff(p);
            if (p.getBoolean(PREF_CALLS, true)) indexCalls(context, store, cutoff);
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

    private static void indexCalls(Context context, CommunicationIndexStore store, long cutoff) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return;
        String[] projection = {CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION};
        try (Cursor c = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, projection,
                CallLog.Calls.DATE + ">?", new String[]{Long.toString(cutoff)}, CallLog.Calls.DATE + " DESC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                String number = c.getString(1);
                String name = c.getString(2);
                long when = c.getLong(3);
                int type = c.getInt(4);
                long duration = c.getLong(5);
                String body = callType(type) + " call · " + duration + " sec";
                store.put("call", Long.toString(c.getLong(0)), TRUECALLER_PACKAGE, number, name, body, when, "");
            }
        } catch (RuntimeException ignored) { }
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
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return;
        String[] projection = {Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE};
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, projection,
                Telephony.Sms.DATE + ">?", new String[]{Long.toString(cutoff)}, Telephony.Sms.DATE + " DESC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                String address = c.getString(1);
                String body = c.getString(2);
                int type = c.getInt(4);
                String label = type == Telephony.Sms.MESSAGE_TYPE_SENT ? "Sent SMS" : "SMS";
                store.put("sms", Long.toString(c.getLong(0)), TRUECALLER_PACKAGE, address, address, label + " · " + (body == null ? "" : body), c.getLong(3), "");
            }
        } catch (RuntimeException ignored) { }
    }

    private static void indexTruecallerNotifications(Context context, CommunicationIndexStore store, long cutoff) {
        List<NotificationHistoryRecord> records = SmartStateStore.queryNotifications(context, TRUECALLER_PACKAGE, null, 10000);
        for (NotificationHistoryRecord r : records) {
            if (r.postTime < cutoff) continue;
            String display = r.title == null || r.title.trim().isEmpty() ? "Truecaller" : r.title;
            String body = r.text == null ? "" : r.text;
            store.put("truecaller", Long.toString(r.dbId), TRUECALLER_PACKAGE, "", display, body, r.postTime, r.notificationId);
        }
    }
}
