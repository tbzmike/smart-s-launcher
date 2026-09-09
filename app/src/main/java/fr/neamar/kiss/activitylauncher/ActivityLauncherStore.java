package fr.neamar.kiss.activitylauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.pojo.ShortcutPojo;

/** Persistent bridge between the foreground-only discovery UI and normal Smart S shortcuts. */
public final class ActivityLauncherStore {
    public static final String PACKAGE_MARKER = "com.tbzmike.smartslauncher.activitylauncher.saved";
    public static final String KIND_ACTIVITY = "activity";
    public static final String KIND_SERVICE = "service";
    public static final String KIND_BROADCAST = "broadcast";

    private static final String INTERNAL_NAME_PREFIX = "activity-launcher-";
    private static final String LABEL_PREF_PREFIX = "activity-launcher-label-";

    private ActivityLauncherStore() { }

    public static boolean isManaged(@Nullable ShortcutRecord record) {
        return record != null && PACKAGE_MARKER.equals(record.packageName)
                && !TextUtils.isEmpty(record.name)
                && record.name.startsWith(INTERNAL_NAME_PREFIX);
    }

    @NonNull
    public static String stablePojoId(@NonNull ShortcutRecord record) {
        if (!isManaged(record)) throw new IllegalArgumentException("Not an Activity Launcher record");
        return ShortcutPojo.SCHEME + record.name.toLowerCase(Locale.ROOT);
    }

    @NonNull
    static String internalNameForUri(@NonNull String launchIntentUri) {
        return INTERNAL_NAME_PREFIX + sha256(launchIntentUri);
    }

    @NonNull
    private static String labelKey(@NonNull ShortcutRecord record) {
        return LABEL_PREF_PREFIX + record.name;
    }

    @NonNull
    public static String displayLabel(@NonNull Context context, @NonNull ShortcutRecord record) {
        String stored = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(labelKey(record), null);
        if (!TextUtils.isEmpty(stored)) return stored;
        return fallbackLabel(context, record);
    }

    @NonNull
    private static String fallbackLabel(@NonNull Context context, @NonNull ShortcutRecord record) {
        try {
            DecodedTarget target = decode(context, record);
            if (target.targetIntent.getComponent() != null) {
                CharSequence label = context.getPackageManager().getActivityInfo(
                        target.targetIntent.getComponent(), 0).loadLabel(context.getPackageManager());
                if (!TextUtils.isEmpty(label)) return label.toString();
                return target.targetIntent.getComponent().getShortClassName();
            }
            if (target.targetIntent.getData() != null) return target.targetIntent.getData().toString();
            if (!TextUtils.isEmpty(target.targetIntent.getAction())) return target.targetIntent.getAction();
        } catch (Exception ignored) { }
        return "Saved target";
    }

    @NonNull
    public static Intent persistentLaunchIntent(@NonNull Context context, @NonNull Intent target,
                                                @NonNull String kind) {
        if (KIND_ACTIVITY.equals(kind)) return new Intent(target);
        if (!KIND_SERVICE.equals(kind) && !KIND_BROADCAST.equals(kind)) {
            throw new IllegalArgumentException("Unsupported target kind: " + kind);
        }
        return ActivityLauncherDispatchActivity.createDispatchIntent(context, target, kind);
    }

    @NonNull
    public static ShortcutRecord save(@NonNull Context context, @NonNull String label,
                                      @NonNull Intent target, @NonNull String kind) {
        Intent persistent = persistentLaunchIntent(context, target, kind);
        String uri = persistent.toUri(0);
        ShortcutRecord record = new ShortcutRecord();
        record.name = internalNameForUri(uri);
        record.packageName = PACKAGE_MARKER;
        record.intentUri = uri;

        DBHelper.insertShortcut(context, record);
        setLabel(context, record, label);
        KissApplication.getApplication(context).getDataHandler().reloadShortcuts();
        return record;
    }

    public static void rename(@NonNull Context context, @NonNull ShortcutRecord record,
                              @NonNull String label) {
        if (!isManaged(record)) return;
        setLabel(context, record, label);
        KissApplication.getApplication(context).getDataHandler().reloadShortcuts();
    }

    private static void setLabel(@NonNull Context context, @NonNull ShortcutRecord record,
                                 @NonNull String label) {
        String clean = label.trim();
        if (clean.isEmpty()) clean = fallbackLabel(context, record);
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString(labelKey(record), clean);
        if (!editor.commit()) throw new IllegalStateException("Unable to persist Activity Launcher label");
    }

    @Nullable
    public static ShortcutRecord find(@NonNull Context context, @NonNull Intent target,
                                      @NonNull String kind) {
        String uri = persistentLaunchIntent(context, target, kind).toUri(0);
        for (ShortcutRecord record : getSaved(context)) {
            if (TextUtils.equals(uri, record.intentUri)) return record;
        }
        return null;
    }

    @NonNull
    public static List<ShortcutRecord> getSaved(@NonNull Context context) {
        List<ShortcutRecord> saved = new ArrayList<>();
        for (ShortcutRecord record : DBHelper.getShortcuts(context, PACKAGE_MARKER)) {
            if (isManaged(record)) saved.add(record);
        }
        saved.sort(Comparator.comparing(record -> displayLabel(context, record),
                String.CASE_INSENSITIVE_ORDER));
        return saved;
    }

    public static void addToHome(@NonNull Context context, @NonNull ShortcutRecord record) {
        if (!isManaged(record)) return;
        // This is an explicit user command, so it intentionally inserts even if passive
        // automatic history recording is frozen.
        DBHelper.insertHistory(context, "", stablePojoId(record));
    }

    public static void addToFavorites(@NonNull Context context, @NonNull ShortcutRecord record) {
        if (!isManaged(record)) return;
        KissApplication.getApplication(context).getDataHandler().addToFavorites(stablePojoId(record));
    }

    public static void remove(@NonNull Context context, @NonNull ShortcutRecord record) {
        if (!isManaged(record)) return;
        String id = stablePojoId(record);
        KissApplication.getApplication(context).getDataHandler().removeFromFavorites(id);
        DBHelper.removeFromHistory(context, id);
        DBHelper.deleteTagsForId(context, id);
        DBHelper.removeShortcut(context, record.packageName, record.intentUri);
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(labelKey(record)).apply();
        KissApplication.getApplication(context).getDataHandler().reloadShortcuts();
    }

    @NonNull
    public static DecodedTarget decode(@NonNull Context context, @NonNull ShortcutRecord record)
            throws URISyntaxException {
        Intent persistent = Intent.parseUri(record.intentUri, 0);
        if (persistent.getComponent() != null
                && context.getPackageName().equals(persistent.getComponent().getPackageName())
                && ActivityLauncherDispatchActivity.class.getName()
                .equals(persistent.getComponent().getClassName())) {
            return ActivityLauncherDispatchActivity.decodeDispatchIntent(persistent);
        }
        return new DecodedTarget(new Intent(persistent), KIND_ACTIVITY);
    }

    @NonNull
    private static String sha256(@NonNull String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[bytes.length * 2];
            final char[] alphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xff;
                hex[i * 2] = alphabet[v >>> 4];
                hex[i * 2 + 1] = alphabet[v & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static final class DecodedTarget {
        public final Intent targetIntent;
        public final String kind;

        DecodedTarget(@NonNull Intent targetIntent, @NonNull String kind) {
            this.targetIntent = targetIntent;
            this.kind = kind;
        }
    }
}
