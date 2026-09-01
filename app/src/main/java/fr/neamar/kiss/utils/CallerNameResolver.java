package fr.neamar.kiss.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a phone number to the best caller name Android currently exposes.
 *
 * Saved contacts remain authoritative. For unknown numbers, the call log's cached caller name is
 * used when READ_CALL_LOG is available. This keeps launcher history independent of a private
 * caller-ID API while still consuming caller names supplied to Android by the active dialer/caller
 * ID stack (including Truecaller when it populates the platform call log).
 */
public final class CallerNameResolver {
    private static final String TAG = CallerNameResolver.class.getSimpleName();
    private static final long CALL_LOG_CACHE_TTL_MS = 30_000L;
    private static final int MAX_CALL_LOG_ROWS = 1000;
    private static final Object CALL_LOG_LOCK = new Object();

    private static volatile Map<String, String> callLogNames = Collections.emptyMap();
    private static volatile long callLogLoadedAt;

    private CallerNameResolver() {}

    @Nullable
    public static String resolve(Context context, String phoneNumber) {
        if (context == null || TextUtils.isEmpty(phoneNumber)) return null;

        String contactName = lookupContactName(context, phoneNumber);
        if (!TextUtils.isEmpty(contactName)) return contactName.trim();

        String callerName = lookupCachedCallerName(context, phoneNumber);
        return TextUtils.isEmpty(callerName) ? null : callerName.trim();
    }

    /** Refresh on the next lookup after a new incoming call has changed the platform call log. */
    public static void invalidateCallLogCache() {
        callLogLoadedAt = 0L;
    }

    @Nullable
    private static String lookupContactName(Context context, String phoneNumber) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return null;

        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber));
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (!TextUtils.isEmpty(name)) return name;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to resolve contact name for phone history", e);
        }
        return null;
    }

    @Nullable
    private static String lookupCachedCallerName(Context context, String phoneNumber) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) return null;

        Map<String, String> names = getCallLogNames(context);
        String fullKey = fullKey(phoneNumber);
        if (!TextUtils.isEmpty(fullKey)) {
            String exact = names.get(fullKey);
            if (!TextUtils.isEmpty(exact)) return exact;
        }

        String tailKey = tailKey(phoneNumber);
        if (!TextUtils.isEmpty(tailKey)) {
            String tail = names.get(tailKey);
            if (!TextUtils.isEmpty(tail)) return tail;
        }
        return null;
    }

    private static Map<String, String> getCallLogNames(Context context) {
        long now = android.os.SystemClock.elapsedRealtime();
        Map<String, String> current = callLogNames;
        if (callLogLoadedAt != 0L && now - callLogLoadedAt < CALL_LOG_CACHE_TTL_MS) return current;

        synchronized (CALL_LOG_LOCK) {
            now = android.os.SystemClock.elapsedRealtime();
            if (callLogLoadedAt != 0L && now - callLogLoadedAt < CALL_LOG_CACHE_TTL_MS) {
                return callLogNames;
            }

            Map<String, String> loaded = new HashMap<>();
            try (Cursor cursor = context.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME},
                    null, null, CallLog.Calls.DATE + " DESC")) {
                int rows = 0;
                if (cursor != null) {
                    int numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                    int nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
                    while (rows++ < MAX_CALL_LOG_ROWS && cursor.moveToNext()) {
                        String number = numberIndex >= 0 ? cursor.getString(numberIndex) : null;
                        String name = nameIndex >= 0 ? cursor.getString(nameIndex) : null;
                        if (TextUtils.isEmpty(number) || TextUtils.isEmpty(name)) continue;
                        name = name.trim();
                        if (name.isEmpty()) continue;

                        String full = fullKey(number);
                        if (!TextUtils.isEmpty(full) && !loaded.containsKey(full)) {
                            loaded.put(full, name);
                        }
                        String tail = tailKey(number);
                        if (!TextUtils.isEmpty(tail) && !loaded.containsKey(tail)) {
                            loaded.put(tail, name);
                        }
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to read cached caller names from call log", e);
            }

            callLogNames = Collections.unmodifiableMap(loaded);
            callLogLoadedAt = now;
            return callLogNames;
        }
    }

    private static String fullKey(String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) return "";
        String normalized = PhoneNumberUtils.normalizeNumber(phoneNumber);
        if (TextUtils.isEmpty(normalized)) return "";
        return "full:" + normalized;
    }

    private static String tailKey(String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) return "";
        String normalized = PhoneNumberUtils.normalizeNumber(phoneNumber);
        if (TextUtils.isEmpty(normalized)) return "";
        String digits = normalized.replaceAll("[^0-9]", "");
        // Nine trailing digits match common local/international representations (for example
        // 082... and +2782...) while avoiding the unsafe very-short-number matching of older APIs.
        if (digits.length() < 9) return "";
        return "tail:" + digits.substring(digits.length() - 9);
    }
}
