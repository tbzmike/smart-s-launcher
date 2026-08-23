package fr.neamar.kiss.index;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves caller names only from data Android actually exposes to Smart S.
 *
 * <p>No private Truecaller database path is hard-coded. Truecaller providers are discovered from
 * PackageManager at runtime and are queried only when Android marks them exported and Smart S holds
 * any declared read permission. If a provider does not expose a readable root query, it is skipped.
 */
final class CallerIdentityResolver {
    private static final String TRUECALLER_PACKAGE = "com.truecaller";
    private static final int MAX_PROVIDER_ROWS = 1500;

    private CallerIdentityResolver() { }

    static Map<String, String> loadReadableTruecallerNames(Context context, List<String> numbers) {
        Map<String, String> result = new HashMap<>();
        if (numbers == null || numbers.isEmpty()) return result;

        Map<String, String> normalizedTargets = new HashMap<>();
        Map<String, String> uniqueSuffixTargets = new HashMap<>();
        Set<String> duplicateSuffixes = new HashSet<>();
        for (String number : numbers) {
            String normalized = normalize(number);
            if (normalized.isEmpty()) continue;
            normalizedTargets.put(normalized, normalized);
            String suffix = suffix(normalized);
            if (!suffix.isEmpty()) {
                if (uniqueSuffixTargets.containsKey(suffix)
                        && !normalized.equals(uniqueSuffixTargets.get(suffix))) {
                    duplicateSuffixes.add(suffix);
                } else {
                    uniqueSuffixTargets.put(suffix, normalized);
                }
            }
        }
        for (String duplicate : duplicateSuffixes) uniqueSuffixTargets.remove(duplicate);

        PackageManager pm = context.getPackageManager();
        PackageInfo info;
        try {
            info = pm.getPackageInfo(TRUECALLER_PACKAGE, PackageManager.GET_PROVIDERS);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return result;
        }
        if (info.providers == null) return result;

        // Keep conflicts separate: if two readable rows disagree for the same exact number, do not
        // silently choose one.
        Set<String> conflicts = new HashSet<>();
        for (ProviderInfo provider : info.providers) {
            if (provider == null || !provider.exported || !provider.enabled
                    || TextUtils.isEmpty(provider.authority) || !canRead(context, provider)) {
                continue;
            }
            String[] authorities = provider.authority.split(";");
            for (String authority : authorities) {
                if (TextUtils.isEmpty(authority)) continue;
                readProvider(context, authority.trim(), normalizedTargets, uniqueSuffixTargets,
                        result, conflicts);
            }
        }
        for (String conflict : conflicts) result.remove(conflict);
        return result;
    }

    static String getTruecallerProviderName(String number, Map<String, String> names) {
        if (names == null || names.isEmpty()) return "";
        String normalized = normalize(number);
        if (normalized.isEmpty()) return "";
        String direct = names.get(normalized);
        return direct == null ? "" : direct;
    }

    static String getAndroidContactName(Context context, String number) {
        if (TextUtils.isEmpty(number)
                || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            return "";
        }
        Uri lookup = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        String[] projection = {ContactsContract.PhoneLookup.DISPLAY_NAME};
        try (Cursor c = context.getContentResolver().query(lookup, projection,
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                return isUsableName(name) ? name.trim() : "";
            }
        } catch (RuntimeException ignored) { }
        return "";
    }

    static boolean isUsableName(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String clean = value.trim();
        if (clean.length() < 2 || clean.length() > 100) return false;
        if (looksLikePhone(clean)) return false;
        String lower = clean.toLowerCase(Locale.ROOT)
                .replace('…', ' ').replace("...", "").trim();
        return !(lower.equals("truecaller")
                || lower.equals("calling")
                || lower.equals("call")
                || lower.equals("unknown")
                || lower.equals("unknown caller")
                || lower.equals("private number")
                || lower.equals("incoming call")
                || lower.equals("outgoing call")
                || lower.equals("missed call")
                || lower.equals("blocked call")
                || lower.equals("rejected call")
                || lower.startsWith("calling ")
                || lower.startsWith("call from ")
                || lower.startsWith("tap to "));
    }

    private static boolean canRead(Context context, ProviderInfo provider) {
        if (TextUtils.isEmpty(provider.readPermission)) return true;
        return ContextCompat.checkSelfPermission(context, provider.readPermission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void readProvider(Context context, String authority,
                                     Map<String, String> normalizedTargets,
                                     Map<String, String> uniqueSuffixTargets,
                                     Map<String, String> result,
                                     Set<String> conflicts) {
        Uri uri = Uri.parse("content://" + authority);
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c == null) return;
            String[] columns = c.getColumnNames();
            List<Integer> numberColumns = new ArrayList<>();
            List<Integer> nameColumns = new ArrayList<>();
            for (int i = 0; i < columns.length; i++) {
                String column = columns[i] == null ? "" : columns[i].toLowerCase(Locale.ROOT);
                if (isNumberColumn(column)) numberColumns.add(i);
                if (isNameColumn(column)) nameColumns.add(i);
            }
            if (numberColumns.isEmpty() || nameColumns.isEmpty()) return;

            int rows = 0;
            while (c.moveToNext() && rows++ < MAX_PROVIDER_ROWS) {
                String target = findTarget(c, numberColumns, normalizedTargets, uniqueSuffixTargets);
                if (target.isEmpty() || conflicts.contains(target)) continue;
                String name = bestName(c, columns, nameColumns);
                if (!isUsableName(name)) continue;

                String previous = result.get(target);
                if (previous == null || previous.equalsIgnoreCase(name)) {
                    result.put(target, name.trim());
                } else {
                    conflicts.add(target);
                    result.remove(target);
                }
            }
        } catch (RuntimeException ignored) {
            // Exported does not necessarily mean the provider supports a root query. Unsupported,
            // permission-gated and provider-specific query contracts are deliberately skipped.
        }
    }

    private static String findTarget(Cursor c, List<Integer> numberColumns,
                                     Map<String, String> normalizedTargets,
                                     Map<String, String> uniqueSuffixTargets) {
        for (int index : numberColumns) {
            String value = safeGet(c, index);
            String normalized = normalize(value);
            if (normalized.isEmpty()) continue;
            if (normalizedTargets.containsKey(normalized)) return normalized;
            String suffix = suffix(normalized);
            String target = uniqueSuffixTargets.get(suffix);
            if (target != null) return target;
        }
        return "";
    }

    private static String bestName(Cursor c, String[] columns, List<Integer> nameColumns) {
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        String first = "";
        String last = "";
        for (int index : nameColumns) {
            String value = safeGet(c, index).trim();
            if (!isUsableName(value)) continue;
            String column = columns[index] == null ? "" : columns[index].toLowerCase(Locale.ROOT);
            if (column.contains("first") && column.contains("name")) first = value;
            if (column.contains("last") && column.contains("name")) last = value;
            int score = nameColumnScore(column);
            if (score > bestScore || (score == bestScore && value.length() > best.length())) {
                best = value;
                bestScore = score;
            }
        }
        if (!first.isEmpty() && !last.isEmpty()) {
            String combined = first + " " + last;
            if (isUsableName(combined) && bestScore < 100) return combined;
        }
        return best;
    }

    private static int nameColumnScore(String column) {
        if (column.equals("display_name") || column.equals("displayname")) return 130;
        if (column.equals("full_name") || column.equals("fullname")) return 125;
        if (column.equals("caller_name") || column.equals("name")) return 120;
        if (column.contains("display") && column.contains("name")) return 110;
        if (column.contains("profile") && column.contains("name")) return 105;
        if (column.contains("name")) return 80;
        if (column.contains("label") || column.contains("title")) return 40;
        return 0;
    }

    private static boolean isNumberColumn(String column) {
        return column.contains("phone") || column.contains("number")
                || column.contains("normalized") || column.equals("address")
                || column.endsWith("_address");
    }

    private static boolean isNameColumn(String column) {
        if (column.contains("package") || column.contains("class") || column.contains("file")) {
            return false;
        }
        return column.contains("name") || column.contains("display")
                || column.contains("label") || column.contains("title");
    }

    private static String safeGet(Cursor c, int index) {
        try {
            return c.isNull(index) ? "" : c.getString(index);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String normalize(String number) {
        if (TextUtils.isEmpty(number)) return "";
        String normalized = PhoneNumberUtils.normalizeNumber(number);
        return normalized == null ? "" : normalized;
    }

    private static String suffix(String normalized) {
        if (TextUtils.isEmpty(normalized)) return "";
        String digits = normalized.replaceAll("[^0-9]", "");
        return digits.length() >= 7 ? digits.substring(digits.length() - 7) : "";
    }

    private static boolean looksLikePhone(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return false;
        String digits = normalized.replaceAll("[^0-9]", "");
        int nonPhoneCharacters = value.replaceAll("[0-9+() .\\-]", "").length();
        return digits.length() >= 7 && nonPhoneCharacters == 0;
    }
}
