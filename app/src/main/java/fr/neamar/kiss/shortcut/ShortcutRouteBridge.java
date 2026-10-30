package fr.neamar.kiss.shortcut;

import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Process;
import android.os.Build;
import android.system.Os;
import android.util.Base64;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Short-lived app_process helper. No service, exported component, or background polling. */
@androidx.annotation.RequiresApi(26)
public final class ShortcutRouteBridge {
    public static final String PREFIX = "SMART_S_SHORTCUT_ROUTE:";
    private ShortcutRouteBridge() { }

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0) throw new SecurityException("Existing root authorization required");
            // A standalone root helper has no application manifest/target SDK. Limit hidden API
            // access to this child VM's Android binder interfaces; never alter device settings.
            try {
                Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
                Object vm = runtime.getDeclaredMethod("getRuntime").invoke(null);
                runtime.getDeclaredMethod("setHiddenApiExemptions", String[].class).invoke(vm,
                        (Object) new String[]{"Landroid/os/ServiceManager;", "Landroid/content/pm/",
                                "Landroid/app/IActivity", "Landroid/app/ActivityTaskManager;"});
            } catch (ReflectiveOperationException unavailable) { }
            String action = args[0], publisher = args[1];
            int number = Integer.parseInt(args[2]);
            if ("capture".equals(action)) {
                String id = new String(Base64.decode(args[3], Base64.URL_SAFE | Base64.NO_WRAP),
                        StandardCharsets.UTF_8);
                // Read ONLY the selected publisher's own ShortcutManager view. Dropping root to
                // that app UID makes Android's normal package/UID check enforce this scope.
                Os.setgid(number);
                Os.setuid(number);
                Object service = service("shortcut", "android.content.pm.IShortcutService$Stub");
                for (Method method : service.getClass().getMethods()) {
                    Object slice;
                    method.setAccessible(true);
                    if (Build.VERSION.SDK_INT >= 30 && method.getName().equals("getShortcuts")
                            && method.getParameterTypes().length == 3) {
                        slice = method.invoke(service, publisher,
                                ShortcutManager.FLAG_MATCH_DYNAMIC | ShortcutManager.FLAG_MATCH_PINNED
                                        | ShortcutManager.FLAG_MATCH_MANIFEST | ShortcutManager.FLAG_MATCH_CACHED,
                                number / 100000);
                    } else if ((method.getName().equals("getDynamicShortcuts")
                            || method.getName().equals("getPinnedShortcuts")
                            || method.getName().equals("getManifestShortcuts"))
                            && method.getParameterTypes().length == 2) {
                        slice = method.invoke(service, publisher, number / 100000);
                    } else continue;
                    List<?> shortcuts = (List<?>) slice.getClass().getMethod("getList").invoke(slice);
                    for (Object value : shortcuts) {
                        ShortcutInfo info = (ShortcutInfo) value;
                        if (publisher.equals(info.getPackage()) && id.equals(info.getId())) {
                            emit(new JSONObject().put("package", publisher).put("id", id)
                                    .put("user", number / 100000).put("timestamp", info.getLastChangedTimestamp())
                                    .put("intents", ShortcutRouteCodec.encode(info.getIntents())));
                            return;
                        }
                    }
                }
                throw new IllegalStateException("Exact shortcut no longer published");
            }
            if (!"launch".equals(action)) throw new IllegalArgumentException("Unknown action");
            if (new java.io.File(args[3]).length() > 262144) throw new IllegalArgumentException("Route too large");
            byte[] bytes = Files.readAllBytes(Paths.get(args[3]));
            if (bytes.length > 262144) throw new IllegalArgumentException("Route too large");
            JSONObject snapshot = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!publisher.equals(snapshot.getString("package")) || number != snapshot.getInt("user")) {
                throw new SecurityException("Route identity mismatch");
            }
            Intent[] intents = ShortcutRouteCodec.decode(snapshot.getJSONArray("intents"));
            for (Intent intent : intents) {
                if (intent.getComponent() == null || !publisher.equals(intent.getComponent().getPackageName())) {
                    throw new SecurityException("Privileged replay requires publisher-owned exact components");
                }
            }
            intents[0].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Object service = Build.VERSION.SDK_INT >= 29
                    ? service("activity_task", "android.app.IActivityTaskManager$Stub")
                    : service("activity", "android.app.IActivityManager$Stub");
            for (Method method : service.getClass().getMethods()) {
                if (!method.getName().equals("startActivities")) continue;
                Class<?>[] types = method.getParameterTypes();
                Object[] parameters = new Object[types.length];
                int string = 0, integers = 0;
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == String.class) parameters[i] = string++ == 0 ? "com.android.shell" : null;
                    else if (types[i] == Intent[].class) parameters[i] = intents;
                    else if (types[i] == String[].class) {
                        String[] mime = new String[intents.length];
                        for (int j = 0; j < mime.length; j++) mime[j] = intents[j].getType();
                        parameters[i] = mime;
                    } else if (types[i] == int.class) { parameters[i] = number; integers++; }
                }
                if (integers != 1) continue;
                method.setAccessible(true);
                int result = (Integer) method.invoke(service, parameters);
                if (result < 0 || result > 3) throw new IllegalStateException("Launch rejected " + result);
                emit(new JSONObject().put("opened", true));
                return;
            }
            throw new IllegalStateException("Unsupported Android launch API");
        } catch (Throwable failure) {
            System.err.println("Smart S exact route bridge: " + failure);
            System.out.println(PREFIX + "ERROR:" + failure);
            System.exit(1);
        }
    }

    private static Object service(String name, String stub) throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, name);
        return Class.forName(stub).getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
    }

    private static void emit(JSONObject value) {
        System.out.println(PREFIX + Base64.encodeToString(value.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP | Base64.URL_SAFE));
        System.out.flush();
        System.exit(0);
    }
}
