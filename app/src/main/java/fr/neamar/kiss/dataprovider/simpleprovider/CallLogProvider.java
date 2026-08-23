package fr.neamar.kiss.dataprovider.simpleprovider;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.pojo.CallLogPojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.Log;

/**
 * Lightweight view of Android's actual call log. These records are deliberately separate from
 * PhonePojo (typed-number actions) and ContactsPojo (address-book contacts), so a recent call can
 * keep its own identity in launcher history and have dialer/Truecaller-specific launch behavior.
 */
public final class CallLogProvider extends SimpleProvider<CallLogPojo> {
    public static final String SCHEME = "calllog://";
    private static final String TAG = CallLogProvider.class.getSimpleName();
    private static final String PREF_ENABLED = "enable-phone-history";
    private static final String PREF_LAST_SYNCED_ID = "smart-calllog-last-synced-id";
    private static final int MAX_CACHE_ROWS = 100;
    private static final int INITIAL_HISTORY_ROWS = 20;
    private static final long REFRESH_THROTTLE_MS = 1500L;

    private final Context context;
    private final SharedPreferences prefs;
    private final Map<Long, CallLogPojo> cache = new LinkedHashMap<>();
    private long lastRefreshMs = 0L;

    public CallLogProvider(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(this.context);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    public boolean hasPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Refreshes cached calls and synchronizes newly observed call rows into normal KISS history. */
    public synchronized void refresh() {
        if (!isEnabled() || !hasPermission()) {
            cache.clear();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastRefreshMs < REFRESH_THROTTLE_MS && !cache.isEmpty()) return;
        lastRefreshMs = now;

        LinkedHashMap<Long, CallLogPojo> fresh = new LinkedHashMap<>();
        String[] projection = new String[]{
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE
        };

        try (Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC")) {
            if (cursor == null) {
                cache.clear();
                return;
            }

            int idCol = cursor.getColumnIndexOrThrow(CallLog.Calls._ID);
            int numberCol = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER);
            int nameCol = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME);
            int dateCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE);
            int durationCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION);
            int typeCol = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE);

            while (cursor.moveToNext() && fresh.size() < MAX_CACHE_ROWS) {
                long id = cursor.getLong(idCol);
                String number = cursor.getString(numberCol);
                String cachedName = cursor.getString(nameCol);
                long date = cursor.getLong(dateCol);
                long duration = cursor.getLong(durationCol);
                int type = cursor.getInt(typeCol);
                if (TextUtils.isEmpty(number)) number = "Unknown number";

                CallLogPojo pojo = new CallLogPojo(
                        id, number, date, duration, type,
                        TextUtils.isEmpty(cachedName) ? number : cachedName);
                // Recent calls should compete strongly with generic web searches while still
                // allowing exact app/contact matches to rank naturally.
                pojo.relevance = 75;
                fresh.put(id, pojo);
            }
        } catch (SecurityException | IllegalArgumentException error) {
            Log.w(TAG, "Unable to read call log", error);
            cache.clear();
            return;
        }

        cache.clear();
        cache.putAll(fresh);
        syncNewCallsIntoHistory();
    }

    private void syncNewCallsIntoHistory() {
        if (cache.isEmpty()) return;

        long lastSyncedId = prefs.getLong(PREF_LAST_SYNCED_ID, 0L);
        List<CallLogPojo> candidates = new ArrayList<>();
        long maxSeenId = lastSyncedId;

        for (CallLogPojo pojo : cache.values()) {
            maxSeenId = Math.max(maxSeenId, pojo.callId);
            if (lastSyncedId == 0L || pojo.callId > lastSyncedId) {
                candidates.add(pojo);
            }
        }

        if (lastSyncedId == 0L && candidates.size() > INITIAL_HISTORY_ROWS) {
            candidates = new ArrayList<>(candidates.subList(0, INITIAL_HISTORY_ROWS));
        }

        // Cache order is newest -> oldest. Insert oldest -> newest so DB _id recency order mirrors
        // the actual call chronology even though KISS's RECENCY mode sorts by history row id.
        Collections.reverse(candidates);
        for (CallLogPojo pojo : candidates) {
            DBHelper.insertExternalHistoryIfMissing(
                    context, "call-log", pojo.getHistoryId(), pojo.callTimestamp);
        }

        if (maxSeenId > lastSyncedId) {
            prefs.edit().putLong(PREF_LAST_SYNCED_ID, maxSeenId).apply();
        }
    }

    public synchronized void forceRefresh() {
        lastRefreshMs = 0L;
        refresh();
    }

    @Override
    public void requestResults(String query, Searcher searcher) {
        refresh();
        if (cache.isEmpty() || TextUtils.isEmpty(query)) return;

        String needle = query.trim().toLowerCase(Locale.getDefault());
        if (needle.isEmpty()) return;
        for (CallLogPojo pojo : cache.values()) {
            String name = pojo.getName() == null ? "" : pojo.getName().toLowerCase(Locale.getDefault());
            String phone = pojo.phoneNumber == null ? "" : pojo.phoneNumber.toLowerCase(Locale.getDefault());
            if (name.contains(needle) || phone.contains(needle)) {
                searcher.addResult(pojo);
            }
        }
    }

    @Override
    public boolean mayFindById(String id) {
        return id != null && id.startsWith(SCHEME);
    }

    @Override
    public synchronized CallLogPojo findById(String id) {
        if (!mayFindById(id)) return null;
        refresh();
        try {
            long callId = Long.parseLong(id.substring(SCHEME.length()));
            return cache.get(callId);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    @Override
    public synchronized List<CallLogPojo> getPojos() {
        refresh();
        return new ArrayList<>(cache.values());
    }
}
