package fr.neamar.kiss.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.json.JSONArray;
import org.json.JSONException;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the exact intent chain exposed by LauncherApps for a shortcut while it is available.
 * Android may stop returning a shortcut while its publisher is frozen/disabled; keeping the
 * platform-provided launch chain lets a remembered launcher result still reach the same destination
 * instead of degrading to a generic app launch.
 */
public final class ShortcutLaunchStore {
    private static final String PREFS = "shortcut-launch-metadata";
    private static final String INTENTS_SUFFIX = "|intents";
    private static final String TARGET_SUFFIX = "|target";

    private ShortcutLaunchStore() { }

    @RequiresApi(Build.VERSION_CODES.O)
    public static void remember(@NonNull Context context, @NonNull ShortcutInfo shortcutInfo,
                                @Nullable String targetPackage) {
        Intent[] intents = shortcutInfo.getIntents();
        if (intents == null || intents.length == 0) return;

        JSONArray serialized = new JSONArray();
        for (Intent intent : intents) {
            if (intent != null) serialized.put(intent.toUri(Intent.URI_INTENT_SCHEME));
        }
        if (serialized.length() == 0) return;

        String key = key(shortcutInfo.getUserHandle(), shortcutInfo.getPackage(), shortcutInfo.getId());
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key + INTENTS_SUFFIX, serialized.toString());
        if (TextUtils.isEmpty(targetPackage)) editor.remove(key + TARGET_SUFFIX);
        else editor.putString(key + TARGET_SUFFIX, targetPackage);
        editor.apply();
    }

    @Nullable
    public static String getTargetPackage(@NonNull Context context,
                                          @NonNull android.os.UserHandle user,
                                          @NonNull String publisherPackage,
                                          @NonNull String shortcutId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(user, publisherPackage, shortcutId) + TARGET_SUFFIX, null);
    }

    public static boolean launch(@NonNull Context context,
                                 @NonNull android.os.UserHandle user,
                                 @NonNull String publisherPackage,
                                 @NonNull String shortcutId,
                                 @Nullable Rect sourceBounds) {
        String baseKey = key(user, publisherPackage, shortcutId);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encoded = prefs.getString(baseKey + INTENTS_SUFFIX, null);
        if (TextUtils.isEmpty(encoded)) return false;

        List<Intent> parsed = new ArrayList<>();
        try {
            JSONArray serialized = new JSONArray(encoded);
            for (int i = 0; i < serialized.length(); i++) {
                String uri = serialized.optString(i, "");
                if (!TextUtils.isEmpty(uri)) {
                    parsed.add(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME));
                }
            }
        } catch (JSONException | URISyntaxException | RuntimeException e) {
            Log.w("ShortcutLaunchStore", "Unable to restore shortcut launch chain", e);
            return false;
        }
        if (parsed.isEmpty()) return false;

        String targetPackage = prefs.getString(baseKey + TARGET_SUFFIX, null);
        if (!TextUtils.isEmpty(targetPackage)
                && !AppLaunchUtils.ensurePackageEnabled(context, targetPackage)) {
            return false;
        }

        Intent[] intents = parsed.toArray(new Intent[0]);
        Intent finalIntent = intents[intents.length - 1];
        if (sourceBounds != null) finalIntent.setSourceBounds(sourceBounds);
        if (!(context instanceof Activity)) intents[0].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            if (intents.length == 1) context.startActivity(intents[0]);
            else context.startActivities(intents);
            return true;
        } catch (RuntimeException e) {
            Log.w("ShortcutLaunchStore", "Stored shortcut destination could not be opened", e);
            return false;
        }
    }

    private static String key(@NonNull android.os.UserHandle user,
                              @NonNull String publisherPackage,
                              @NonNull String shortcutId) {
        return user.hashCode() + "|" + publisherPackage + "|" + shortcutId;
    }
}
