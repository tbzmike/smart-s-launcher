package fr.neamar.kiss.shortcut;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.FrozenAppPreferences;
import fr.neamar.kiss.utils.ShortcutUtil;

/** Exact selected routes survive Android deleting dynamic IDs during package disable/hide. */
@androidx.annotation.RequiresApi(26)
public final class SavedShortcutRoutes {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(0, 1, 15L,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), r -> new Thread(r, "smart-s-exact-route"));
    private SavedShortcutRoutes() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("smart-shortcut-exact-routes", Context.MODE_PRIVATE);
    }

    private static String key(ShortcutPojo pojo) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((pojo.packageName + '|'
                    + pojo.getOreoId() + '|' + pojo.getUserHandle().getRealHandle().hashCode())
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(digest, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Nullable private static JSONObject read(Context context, ShortcutPojo pojo) {
        try {
            String json = prefs(context).getString(key(pojo), "");
            if (json.isEmpty()) return null;
            JSONObject route = new JSONObject(json);
            if (!pojo.packageName.equals(route.getString("package"))
                    || !pojo.getOreoId().equals(route.getString("id"))
                    || route.getInt("user") != android.os.Process.myUid() / 100000) return null;
            return route;
        } catch (Exception invalid) { return null; }
    }

    public static boolean has(Context context, ShortcutPojo pojo) { return read(context, pojo) != null; }

    public static void discard(Context context, ShortcutPojo pojo) {
        prefs(context).edit().remove(key(pojo)).apply();
    }

    /** Return true only when first-launch preservation is now running asynchronously. */
    public static boolean preserveBeforeLaunch(Context context, ShortcutPojo pojo, Runnable ready) {
        if (!pojo.getUserHandle().isCurrentUser() || !FrozenAppPreferences.autoEnable(context)
                || ShortcutUtil.isIceBoxPublisher(context, pojo.packageName)) return false;
        ShortcutInfo live = ShortcutUtil.getShortCut(context, pojo.getUserHandle().getRealHandle(),
                pojo.packageName, pojo.getOreoId());
        if (live == null) return false;
        JSONObject saved = read(context, pojo);
        if (saved != null && saved.optLong("timestamp") == live.getLastChangedTimestamp()) return false;
        Context app = context.getApplicationContext();
        try {
            WORK.execute(() -> {
                try {
                    if (KissApplication.getApplication(app).getRootHandler().isRootAvailable()) {
                        ShortcutUtil.retainShortcut(app, live);
                        ApplicationInfo publisher = app.getPackageManager().getApplicationInfo(
                                pojo.packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
                        String id = Base64.encodeToString(pojo.getOreoId().getBytes(StandardCharsets.UTF_8),
                                Base64.NO_WRAP | Base64.URL_SAFE);
                        String output = KissApplication.getApplication(app).getRootHandler().readPrivilegedOutput(
                                bridgeCommand(app, "capture", pojo.packageName, publisher.uid, id));
                        JSONObject route = response(output);
                        if (route != null && pojo.packageName.equals(route.optString("package"))
                                && pojo.getOreoId().equals(route.optString("id"))) {
                            ShortcutRouteCodec.decode(route.getJSONArray("intents"));
                            prefs(app).edit().putString(key(pojo), route.toString()).apply();
                        }
                    }
                } catch (Exception unavailable) { /* Native route remains available without root. */ }
                MAIN.post(ready);
            });
            return true;
        } catch (RuntimeException busy) { return false; }
    }

    public static void open(Context context, ShortcutPojo pojo, Consumer<Boolean> completed) {
        JSONObject route = read(context, pojo);
        if (route == null) { MAIN.post(() -> completed.accept(false)); return; }
        openSnapshot(context, pojo, route, completed);
    }

    /** Legacy app shortcuts already contain their full exact Intent, including private targets. */
    public static boolean openLegacy(Context context, ShortcutPojo pojo, Intent exact,
                                      Consumer<Boolean> completed) {
        if (!pojo.getUserHandle().isCurrentUser() || !FrozenAppPreferences.autoEnable(context)
                || exact.getComponent() == null
                || !pojo.packageName.equals(exact.getComponent().getPackageName())) return false;
        try {
            JSONObject route = new JSONObject().put("package", pojo.packageName).put("id", pojo.id)
                    .put("user", android.os.Process.myUid() / 100000)
                    .put("intents", ShortcutRouteCodec.encode(new Intent[]{exact}));
            openSnapshot(context, pojo, route, completed);
            return true;
        } catch (Exception invalid) { return false; }
    }

    private static void openSnapshot(Context context, ShortcutPojo pojo, JSONObject route,
                                      Consumer<Boolean> completed) {
        try {
            Intent[] intents = ShortcutRouteCodec.decode(route.getJSONArray("intents"));
            intents[0].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivities(intents);
            MAIN.post(() -> completed.accept(true));
            return;
        } catch (Exception exactComponentNeedsPrivilege) { /* Try the same captured route with root. */ }
        Context app = context.getApplicationContext();
        try {
            WORK.execute(() -> {
                boolean opened = false;
                File input = null;
                try {
                    Intent[] intents = ShortcutRouteCodec.decode(route.getJSONArray("intents"));
                    boolean valid = true;
                    for (Intent intent : intents) {
                        if (intent.getComponent() == null || !pojo.packageName.equals(
                                intent.getComponent().getPackageName())) { valid = false; break; }
                        if (!AppLaunchUtils.isActivityEnabledNow(app, intent.getComponent())) {
                            KissApplication.getApplication(app).getRootHandler().enableComponent(
                                    pojo.packageName, intent.getComponent().getClassName(), route.getInt("user"));
                            valid &= AppLaunchUtils.isActivityEnabledNow(app, intent.getComponent());
                        }
                    }
                    if (valid) {
                        input = File.createTempFile("exact-shortcut-", ".json", app.getCacheDir());
                        try (FileOutputStream out = new FileOutputStream(input)) {
                            out.write(route.toString().getBytes(StandardCharsets.UTF_8));
                        }
                        JSONObject result = response(KissApplication.getApplication(app).getRootHandler()
                                .readPrivilegedOutput(bridgeCommand(app, "launch", pojo.packageName,
                                        route.getInt("user"), input.getAbsolutePath())));
                        opened = result != null && result.optBoolean("opened");
                    }
                } catch (Exception unavailable) { /* Preserve the route for a later authorized retry. */ }
                finally { if (input != null) input.delete(); }
                boolean result = opened;
                MAIN.post(() -> completed.accept(result));
            });
        } catch (RuntimeException busy) { MAIN.post(() -> completed.accept(false)); }
    }

    private static String bridgeCommand(Context context, String action, String publisher, int number,
                                         String argument) {
        if (!publisher.matches("[A-Za-z0-9_.$]+")) throw new IllegalArgumentException("Invalid publisher");
        return "/system/bin/env " + quote("CLASSPATH=" + context.getApplicationInfo().sourceDir)
                + " /system/bin/app_process /system/bin " + ShortcutRouteBridge.class.getName()
                + " " + action + " " + quote(publisher) + " " + number + " " + quote(argument);
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    @Nullable private static JSONObject response(String output) {
        try {
            for (String line : output.split("\n")) if (line.startsWith(ShortcutRouteBridge.PREFIX)) {
                return new JSONObject(new String(Base64.decode(line.substring(ShortcutRouteBridge.PREFIX.length()),
                        Base64.NO_WRAP | Base64.URL_SAFE), StandardCharsets.UTF_8));
            }
        } catch (Exception invalid) { }
        return null;
    }
}
