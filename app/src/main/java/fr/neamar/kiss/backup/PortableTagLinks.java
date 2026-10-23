package fr.neamar.kiss.backup;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.UserManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.UserHandle;

/** Extra portable identity information alongside the byte-for-byte database snapshot.
 * The SQLite tags and shortcut catalog remain authoritative; this sidecar only repairs
 * identities that Android legitimately changes on a different user profile or app upgrade.
 */
public final class PortableTagLinks {
    private static final String TAG = "PortableTagLinks";
    public static final String PENDING_FILENAME = ".smart-s-pending-tag-links.json";
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private PortableTagLinks() { }

    @NonNull
    public static JSONObject capture(@NonNull Context context) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("schema", 1);
        JSONArray links = new JSONArray();
        Map<String, String> tags = DBHelper.loadTags(context);
        List<ShortcutRecord> shortcuts = DBHelper.getShortcuts(context);
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            String id = entry.getKey();
            JSONObject link = new JSONObject();
            link.put("id", id);
            link.put("tags", entry.getValue());
            if (id.startsWith("app://")) {
                String component = TagLinkIdentity.appComponent(id);
                ComponentName app = ComponentName.unflattenFromString(component);
                if (app != null) {
                    link.put("kind", "app");
                    link.put("package", app.getPackageName());
                    link.put("class", app.getClassName());
                }
            } else if (id.startsWith(ShortcutPojo.SCHEME)) {
                ShortcutRecord match = null;
                for (ShortcutRecord shortcut : shortcuts) {
                    if (shortcut == null || shortcut.packageName == null || shortcut.intentUri == null) continue;
                    if (TagLinkIdentity.belongsToShortcut(id, shortcut.packageName,
                            shortcut.intentUri, shortcut.name)) {
                        match = shortcut;
                        break;
                    }
                }
                if (match != null) {
                    link.put("kind", "shortcut");
                    link.put("package", match.packageName);
                    link.put("intentUri", match.intentUri);
                    link.put("targetPackage", match.targetPackage == null ? "" : match.targetPackage);
                }
            }
            links.put(link);
        }
        out.put("links", links);
        out.put("count", links.length());
        return out;
    }

    /** Invoked on the first fresh process after restore, BEFORE DataHandler loads its tag cache. */
    public static void repairPending(@NonNull Context context) {
        File pending = new File(context.getFilesDir(), PENDING_FILENAME);
        if (!pending.isFile()) return;
        try {
            JSONObject payload = new JSONObject(readBounded(pending));
            if (payload.optInt("schema", -1) != 1) throw new JSONException("Unknown tag-link schema");
            JSONArray links = payload.getJSONArray("links");
            if (links.length() != payload.optInt("count", -1)) throw new JSONException("Tag-link count mismatch");
            Map<String, String> remaps = new HashMap<>();
            LauncherApps apps = ContextCompat.getSystemService(context, LauncherApps.class);
            UserManager users = ContextCompat.getSystemService(context, UserManager.class);
            if (apps != null && users != null) {
                for (int i = 0; i < links.length(); i++) {
                    JSONObject item = links.getJSONObject(i);
                    String oldId = item.getString("id");
                    String newId = resolve(context, apps, users, item);
                    if (newId != null && !oldId.equals(newId)) remaps.put(oldId, newId);
                }
            }
            if (!remaps.isEmpty()) {
                DBHelper.remapRestoredLinks(context, remaps);
                remapFavoriteIds(context, remaps);
            }
            if (!pending.delete()) Log.w(TAG, "Pending tag-link file could not be removed");
        } catch (Exception e) {
            // Never delete/overwrite tags on a failed or ambiguous rebind.
            Log.e(TAG, "Portable tag links could not be reconciled; preserved original IDs", e);
        }
    }

    /** Do not guess mappings between owner and work profiles. Only a unique, verified app
     * component or Android-published shortcut in the SAME profile category can be rebound. */
    private static String resolve(Context context, LauncherApps apps, UserManager users, JSONObject item)
            throws JSONException {
        String id = item.getString("id");
        String kind = item.optString("kind", "");
        boolean scoped = "app".equals(kind)
                ? TagLinkIdentity.hasAppProfile(id) : "shortcut".equals(kind)
                && TagLinkIdentity.hasShortcutProfile(id, item.optString("package", ""),
                        item.optString("intentUri", ""));
        if (!"app".equals(kind) && !"shortcut".equals(kind)) return null;
        String pkg = item.optString("package", "");
        if (pkg.isEmpty()) return null;
        // Debug and production Smart S variants share the same launcher code but have
        // distinct Android package names. Reconnect only their own verified app targets.
        if (BackupPackagePolicy.compatible(context.getPackageName(), pkg)
                && !context.getPackageName().equals(pkg)) {
            pkg = context.getPackageName();
        }
        List<String> matches = new ArrayList<>();
        for (android.os.UserHandle profile : users.getUserProfiles()) {
            UserHandle user = new UserHandle(context, profile);
            if (user.isCurrentUser() == scoped) continue;
            try {
                if ("app".equals(kind)) {
                    String activity = item.optString("class", "");
                    List<LauncherActivityInfo> all = apps.getActivityList(pkg, profile);
                    if (all == null) continue;
                    List<LauncherActivityInfo> same = new ArrayList<>();
                    for (LauncherActivityInfo info : all) {
                        if (pkg.equals(info.getComponentName().getPackageName())) same.add(info);
                    }
                    for (LauncherActivityInfo info : same) {
                        if (activity.equals(info.getComponentName().getClassName())) {
                            matches.add("app://" + user.addUserSuffixToString(
                                    pkg + "/" + activity, '#'));
                            break;
                        }
                    }
                    if (matches.isEmpty() && same.size() == 1 && !scoped) {
                        // Safe app upgrade fallback: publisher has exactly one current launch activity.
                        matches.add("app://" + user.addUserSuffixToString(
                                pkg + "/" + same.get(0).getComponentName().getClassName(), '#'));
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    String intentUri = item.optString("intentUri", "");
                    if (!intentUri.startsWith(ShortcutPojo.OREO_PREFIX)) continue;
                    String shortcutId = intentUri.substring(ShortcutPojo.OREO_PREFIX.length());
                    LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
                    q.setPackage(pkg);
                    q.setShortcutIds(java.util.Collections.singletonList(shortcutId));
                    q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                    if (!apps.hasShortcutHostPermission()) continue;
                    List<ShortcutInfo> available = apps.getShortcuts(q, profile);
                    if (available == null) continue;
                    for (ShortcutInfo info : available) {
                        if (pkg.equals(info.getPackage()) && shortcutId.equals(info.getId())) {
                            matches.add(user.addUserSuffixToString(
                                    ShortcutPojo.SCHEME + pkg + "/" + intentUri, '/'));
                            break;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // Package not installed, profile locked, permission unavailable, or publisher
                // removed the shortcut. Keep its original ID without attaching tags elsewhere.
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static void remapFavoriteIds(Context context, Map<String, String> remaps) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String original = prefs.getString("favorite-apps-list", "");
        String[] ids = original.split(";", -1);
        boolean changed = false;
        for (int i = 0; i < ids.length; i++) {
            String replacement = remaps.get(ids[i]);
            if (replacement != null) { ids[i] = replacement; changed = true; }
        }
        if (changed) prefs.edit().putString("favorite-apps-list",
                android.text.TextUtils.join(";", ids)).commit();
    }

    private static String readBounded(File source) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > MAX_BYTES) throw new IOException("Tag-link metadata too large");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
