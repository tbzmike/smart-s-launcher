package fr.neamar.kiss.freeform;

import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Process;
import androidx.annotation.RequiresApi;

import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Short-lived, root-authorized app_process entry point. Signatures match AOSP 35/36
 * IActivityTaskManager, TaskInfo and WindowContainerTransaction. No package force-stop,
 * global settings, raw binder transaction numbers or simulated activity restarts.
 */
@RequiresApi(35)
public final class NativeTaskBridge {
    public static final String PREFIX = "SMART_S_FREEFORM:";
    private NativeTaskBridge() {}

    public static void main(String[] args) {
        // The helper stays alive only for the active gesture. No organizer is registered and no
        // SystemUI task leash is stolen. EOF closes the helper when the launcher dies.
        if (args.length == 4 && "gesture".equals(args[0])) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("end".equals(line)) break;
                    String[] parts = line.split(" ");
                    if (parts.length != 5 || !("live".equals(parts[0]) || "resize".equals(parts[0]))) break;
                    run(new String[]{parts[0], args[1], args[2], args[3],
                            parts[1], parts[2], parts[3], parts[4]});
                }
            } catch (Exception ignored) { }
        } else run(args);
        System.exit(0);
    }

    private static void run(String[] args) {
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root required");
            if (args.length < 2) throw new IllegalArgumentException("Missing action or profile");
            Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
            Object vm = runtime.getDeclaredMethod("getRuntime").invoke(null);
            runtime.getDeclaredMethod("setHiddenApiExemptions", String[].class).invoke(vm,
                    (Object) new String[]{"Landroid/os/ServiceManager;", "Landroid/app/IActivityTaskManager",
                            "Landroid/app/ActivityOptions;", "Landroid/app/TaskInfo;", "Landroid/app/ActivityManager$RunningTaskInfo;",
                            "Landroid/content/res/Configuration;->windowConfiguration",
                            "Landroid/hardware/display/", "Landroid/view/DisplayInfo;", "Landroid/hardware/input/", "Landroid/app/WindowConfiguration;", "Landroid/window/"});
            Class<?> atm = Class.forName("android.app.IActivityTaskManager");
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                    .invoke(null, "activity_task");
            Object service = Class.forName("android.app.IActivityTaskManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            int user = Integer.parseInt(args[1]);
            if (user < 0) throw new IllegalArgumentException("Invalid user");
            String action = args[0];
            List<?> tasks = tasks(atm, service);
            if ("identities".equals(action)) {
                JSONArray rows = new JSONArray();
                for (Object item : tasks) {
                    JSONObject info = describe(item);
                    if (info != null && info.getInt("user") == user) rows.put(info);
                }
                emit(new JSONObject().put("ok", true).put("tasks", rows));
                return;
            }
            if ("list".equals(action) || "frames".equals(action)) {
                JSONArray rows = new JSONArray();
                List<?> windows = visibleWindows();
                List<WindowFrameLookup.Window> layout = null;
                for (Object item : tasks) {
                    JSONObject info = describe(item);
                    if (info != null && info.getInt("user") == user) {
                        if (info.getInt("display") != 0) { rows.put(info); continue; }
                        addFrame(info, item, tasks, windows);
                        if (!info.has("vl") && info.getBoolean("visible")
                                && info.getInt("type") == 1) {
                            if (layout == null) layout = windowLayouts();
                            addLayoutFrame(info, layout, windows);
                        }
                        rows.put(info);
                    }
                }
                if ("frames".equals(action)) {
                    emit(new JSONObject().put("ok", true).put("tasks", rows));
                    return;
                }
                JSONArray decorations = decorationFrames(windows);
                if (decorations.length() == 0) {
                    if (layout == null) layout = windowLayouts();
                    decorations = layoutDecorations(layout);
                }
                emit(new JSONObject().put("ok", true).put("tasks", rows)
                        .put("decorations", decorations));
                return;
            }
            if (args.length != 8) throw new IllegalArgumentException("Malformed task operation");
            int id = Integer.parseInt(args[2]);
            String component = args[3];
            Object selected = null;
            JSONObject info = null;
            for (Object item : tasks) {
                JSONObject row = describe(item);
                if (row != null && row.getInt("id") == id && row.getInt("user") == user
                        && component.equals(row.getString("component"))
                        && row.getInt("type") == 1) {
                    selected = item;
                    info = row;
                    break;
                }
            }
            if (selected == null || info == null) throw new IllegalStateException("Task identity changed");
            int mode = info.getInt("mode");
            if (mode != 1 && mode != 5) throw new IllegalStateException("Task is not fullscreen/freeform");
            int display = info.getInt("display");
            if (action.equals("parkFullscreen") || action.equals("parkPopup")) {
                int destination=Integer.parseInt(args[4]);
                if (display!=0 || destination<=0 || !liveDisplay(destination,id)
                        || (action.equals("parkFullscreen") && (mode!=1 || info.getBoolean("focused"))))
                    throw new IllegalStateException("Task is not eligible for bubbling");
                atm.getMethod("moveRootTaskToDisplay",int.class,int.class).invoke(service,id,destination);
                for (int attempt = 0; attempt < 10; attempt++) {
                    for (Object item : tasks(atm, service)) {
                        JSONObject moved = describe(item);
                        if (moved != null && moved.getInt("id") == id && moved.getInt("user") == user
                                && component.equals(moved.getString("component")) && moved.getInt("display") == destination) {
                            emit(new JSONObject().put("ok", true));
                            return;
                        }
                    }
                    android.os.SystemClock.sleep(32);
                }
                emit(new JSONObject().put("ok", false));
                return;
            }
            if (display!=0 && !action.equals("close") && !action.equals("closeFullscreen")) {
                if (!(action.startsWith("restore") || action.equals("switchFullscreen") || action.equals("maximize")))
                    throw new IllegalStateException("Unsupported live task operation");
                // Bring the existing task and its activity surface forward together on the phone display.
                atm.getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(service, id, 0);
                android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                options.setLaunchDisplayId(0);
                boolean popup = action.startsWith("restore");
                android.app.ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class)
                        .invoke(options, popup ? 5 : 1);
                if (popup) options.setLaunchBounds(new Rect(Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                        Integer.parseInt(args[6]), Integer.parseInt(args[7])));
                atm.getMethod("startActivityFromRecents", int.class, android.os.Bundle.class)
                        .invoke(service, id, options.toBundle());
                if(action.equals("switchFullscreen"))action="maximize";
            }
            if ("switchFullscreen".equals(action) || "closeFullscreen".equals(action)) {
                if (display == 0 && (mode != 1 || ("closeFullscreen".equals(action) && info.getBoolean("focused")))) throw new IllegalStateException("Task no longer eligible");
                action = "switchFullscreen".equals(action) ? "focus" : "close";
            }
            if ("back".equals(action)) {
                if (mode != 5 || !info.getBoolean("visible")) throw new IllegalStateException("Window unavailable");
                if (!info.getBoolean("focused")) atm.getMethod("setFocusedTask", int.class).invoke(service, id);
                android.content.pm.ActivityInfo target = null;
                for (int attempt = 0; attempt < 12 && target == null; attempt++) {
                    for (Object item : tasks(atm, service)) {
                        JSONObject row = describe(item);
                        if (row != null && row.getInt("id") == id && row.getInt("user") == user
                                && component.equals(row.getString("component")) && row.getBoolean("focused")
                                && row.getBoolean("visible")) {
                            target = (android.content.pm.ActivityInfo) item.getClass().getField("topActivityInfo").get(item);
                            break;
                        }
                    }
                    if (target == null) android.os.SystemClock.sleep(16);
                }
                if (target == null || target.applicationInfo == null) throw new IllegalStateException("No focused target");
                Object inputBinder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                        .invoke(null, "input");
                Object input = Class.forName("android.hardware.input.IInputManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, inputBinder);
                Method inject = Class.forName("android.hardware.input.IInputManager")
                        .getMethod("injectInputEventToTarget", android.view.InputEvent.class, int.class, int.class);
                long down = android.os.SystemClock.uptimeMillis();
                for (int keyAction : new int[]{android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP}) {
                    android.view.KeyEvent event = new android.view.KeyEvent(down, android.os.SystemClock.uptimeMillis(),
                            keyAction, android.view.KeyEvent.KEYCODE_BACK, 0, 0, -1, 0,
                            android.view.KeyEvent.FLAG_FROM_SYSTEM, android.view.InputDevice.SOURCE_KEYBOARD);
                    if (!(Boolean) inject.invoke(input, event, 2, target.applicationInfo.uid))
                        throw new IllegalStateException("Back delivery failed");
                }
                emit(new JSONObject().put("ok", true));
                return;
            }
            if ("close".equals(action)) {
                boolean removed = (Boolean) atm.getMethod("removeTask", int.class).invoke(service, id);
                emit(new JSONObject().put("ok", removed));
                return;
            }
            Rect bounds = new Rect(Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                    Integer.parseInt(args[6]), Integer.parseInt(args[7]));
            Class<?> tokenType = Class.forName("android.window.WindowContainerToken");
            Class<?> transactionType = Class.forName("android.window.WindowContainerTransaction");
            Object token = selected.getClass().getField("token").get(selected);
            if (token == null) throw new IllegalStateException("Task has no container token");
            Object transaction = transactionType.getConstructor().newInstance();
            Method hidden = transactionType.getMethod("setHidden", tokenType, boolean.class);
            Method reorder = transactionType.getMethod("reorder", tokenType, boolean.class);
            Method windowMode = transactionType.getMethod("setWindowingMode", tokenType, int.class);
            Method setBounds = transactionType.getMethod("setBounds", tokenType, Rect.class);
            boolean keepAbove = action.endsWith("Pinned");
            if (keepAbove) action = action.substring(0, action.length() - "Pinned".length());
            if (keepAbove || "unpin".equals(action) || "minimize".equals(action) || "maximize".equals(action)) {
                transactionType.getMethod("setAlwaysOnTop", tokenType, boolean.class)
                        .invoke(transaction, token, keepAbove);
            }
            boolean focus = false;
            switch (action) {
                case "unpin":
                    break;
                case "live":
                    if (mode != 5 || bounds.isEmpty()) throw new IllegalArgumentException("Not a resizable popup");
                    // Transition-aware resize path updates actual task surfaces, not just the
                    // requested override configuration. SystemUI retains its organizer.
                    atm.getMethod("resizeTask", int.class, Rect.class, int.class)
                            .invoke(service, id, bounds, 1 /* RESIZE_MODE_USER */);
                    emit(observe(atm, service, id, component, user, bounds, true));
                    return;
                case "minimize":
                    hidden.invoke(transaction, token, true);
                    reorder.invoke(transaction, token, false);
                    break;
                case "restore":
                    if (bounds.isEmpty()) throw new IllegalArgumentException("Missing restore bounds");
                    hidden.invoke(transaction, token, false);
                    windowMode.invoke(transaction, token, 5);
                    setBounds.invoke(transaction, token, bounds);
                    reorder.invoke(transaction, token, true);
                    focus = true;
                    break;
                case "maximize":
                    hidden.invoke(transaction, token, false);
                    windowMode.invoke(transaction, token, 1);
                    setBounds.invoke(transaction, token, new Rect());
                    reorder.invoke(transaction, token, true);
                    focus = true;
                    break;
                case "resize":
                    if (mode != 5 || bounds.isEmpty()) throw new IllegalArgumentException("Not a resizable popup");
                    setBounds.invoke(transaction, token, bounds);
                    reorder.invoke(transaction, token, true);
                    focus = true;
                    break;
                case "focus":
                    reorder.invoke(transaction, token, true);
                    focus = true;
                    break;
                default: throw new IllegalArgumentException("Unknown task operation");
            }
            if (android.os.Build.VERSION.SDK_INT == 35 && (action.equals("restore")
                    || action.equals("maximize") || action.equals("resize"))) {
                Rect surfaceBounds = action.equals("maximize") ? new Rect() : bounds;
                transactionType.getMethod("setBoundsChangeTransaction", tokenType, Rect.class)
                        .invoke(transaction, token, surfaceBounds);
            }
            Object organizer = atm.getMethod("getWindowOrganizerController").invoke(service);
            Class<?> organizerType = Class.forName("android.window.IWindowOrganizerController");
            // Shell transitions update crop/position of organized task surfaces as well as
            // configuration. AOSP falls back to legacy apply when no transition player exists.
            if ("minimize".equals(action) || "restore".equals(action) || "maximize".equals(action) || "resize".equals(action)) {
                int transition = "minimize".equals(action) ? 4 /* TRANSIT_TO_BACK */
                        : "restore".equals(action) && (display != 0 || !info.getBoolean("visible")) ? 3 /* TRANSIT_TO_FRONT */
                        : 6 /* TRANSIT_CHANGE */;
                organizerType.getMethod("startNewTransition", int.class, transactionType)
                        .invoke(organizer, transition, transaction);
            } else {
                organizerType.getMethod("applyTransaction", transactionType).invoke(organizer, transaction);
            }
            if (focus) {
                // The transaction itself succeeded; focus is advisory on ROMs with desktop policy.
                try { if (display != 0 || (!"restore".equals(action) && !"maximize".equals(action) && !"resize".equals(action)))
                    atm.getMethod("setFocusedTask", int.class).invoke(service, id); }
                catch (ReflectiveOperationException ignored) { }
            }
            if ("resize".equals(action) || "restore".equals(action)) {
                emit(observe(atm, service, id, component, user, bounds, false));
            } else emit(new JSONObject().put("ok", true));
        } catch (Throwable failure) {
            try { emit(new JSONObject().put("ok", false).put("error", failure.toString())); }
            catch (Exception ignored) { System.exit(1); }
        }
    }

    private static List<?> tasks(Class<?> atm, Object service) throws Exception {
        return (List<?>) atm.getMethod("getTasks", int.class, boolean.class, boolean.class, int.class)
                .invoke(service, 200, false, false, -1);
    }

    private static boolean liveDisplay(int display,int task) throws Exception {
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"display");
        Object dm=Class.forName("android.hardware.display.IDisplayManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        Object info=Class.forName("android.hardware.display.IDisplayManager").getMethod("getDisplayInfo",int.class).invoke(dm,display);
        if(info==null)return false;
        String owner=(String)info.getClass().getField("ownerPackageName").get(info);
        return ("com.tbzmike.smartslauncher".equals(owner)||"com.tbzmike.smartslauncher.debug".equals(owner))
                && ("Smart S live task "+task).equals(info.getClass().getField("name").get(info));
    }

    private static JSONObject describe(Object task) throws Exception {
        Class<?> cls = task.getClass();
        ComponentName component = (ComponentName) cls.getField("baseActivity").get(task);
        if (component == null) return null;
        int display = cls.getField("displayId").getInt(task);
        if (display != 0 && !liveDisplay(display,cls.getField("taskId").getInt(task))) return null;
        Configuration configuration = (Configuration) cls.getField("configuration").get(task);
        Object wc = Configuration.class.getField("windowConfiguration").get(configuration);
        Rect bounds = (Rect) wc.getClass().getMethod("getBounds").invoke(wc);
        int mode = (Integer) cls.getMethod("getWindowingMode").invoke(task);
        int type = (Integer) cls.getMethod("getActivityType").invoke(task);
        return new JSONObject().put("display",display).put("id", cls.getField("taskId").getInt(task))
                .put("user", cls.getField("userId").getInt(task))
                .put("component", component.flattenToString()).put("pkg", component.getPackageName())
                .put("mode", mode).put("type", type)
                .put("visible", cls.getField("isVisible").getBoolean(task))
                .put("focused", cls.getField("isFocused").getBoolean(task))
                .put("l", bounds.left).put("t", bounds.top).put("r", bounds.right).put("b", bounds.bottom);
    }

    private static JSONObject observe(Class<?> atm, Object service, int id, String component,
                                      int user, Rect expected, boolean live) throws Exception {
        JSONObject last = null;
        long deadline = android.os.SystemClock.elapsedRealtime() + 1400;
        for (int attempt = 0; attempt < 12 && android.os.SystemClock.elapsedRealtime() < deadline; attempt++) {
            if (attempt != 0) android.os.SystemClock.sleep(live ? 16 : 40);
            List<?> rows = tasks(atm, service);
            for (Object item : rows) {
                JSONObject row = describe(item);
                if (row == null || row.getInt("id") != id || row.getInt("user") != user
                        || !component.equals(row.getString("component"))) continue;
                List<?> windows = visibleWindows();
                addFrame(row, item, rows, windows);
                if (!row.has("vl") && row.getBoolean("visible"))
                    addLayoutFrame(row, windowLayouts(), windows);
                last = row;
                if (row.getInt("mode") == 5 && row.has("vl") && row.getInt("l") == expected.left
                        && row.getInt("t") == expected.top && row.getInt("r") == expected.right
                        && row.getInt("b") == expected.bottom
                        && Math.abs(row.getInt("vl") - expected.left) <= 12
                        && Math.abs(row.getInt("vt") - expected.top) <= 12
                        && Math.abs(row.getInt("vr") - expected.right) <= 12
                        && Math.abs(row.getInt("vb") - expected.bottom) <= 12) {
                    return new JSONObject().put("ok", true).put("task", row);
                }
            }
        }
        return new JSONObject().put("ok", last != null).put("task", last);
    }

    /** SurfaceFlinger input-window frames in logical display coordinates, independent of TaskInfo. */
    private static List<?> visibleWindows() {
        try {
            Class<?> type = Class.forName("android.window.WindowInfosListenerForTest");
            Object listener = type.getConstructor().newInstance();
            java.util.concurrent.atomic.AtomicReference<List<?>> rows = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.function.Consumer<List<?>> callback = rows::set;
            Method add = type.getMethod("addWindowInfosListener", java.util.function.Consumer.class);
            Method remove = type.getMethod("removeWindowInfosListener", java.util.function.Consumer.class);
            try {
                add.invoke(listener, callback);
                return rows.get();
            } finally { remove.invoke(listener, callback); }
        } catch (Throwable unavailable) { return null; }
    }

    /** Independent WindowManager fallback, using exact task/user identity and visible main windows.
     * No reliance on an optional test API or on the application title matching its component. */
    private static List<WindowFrameLookup.Window> windowLayouts() {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "-t", "1", "window", "-a", "windows")
                    .redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.InputStream input = process.getInputStream();
            byte[] buffer = new byte[4096];
            long deadline = android.os.SystemClock.elapsedRealtime() + 1200;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                while (input.available() > 0) {
                    int n = input.read(buffer);
                    if (n < 0) break;
                    bytes.write(buffer, 0, n);
                    if (bytes.size() > 2_000_000) return java.util.Collections.emptyList();
                }
                if (!process.isAlive()) {
                    while (input.available() > 0) {
                        int n = input.read(buffer);
                        if (n < 0) break;
                        bytes.write(buffer, 0, n);
                    }
                    if (process.exitValue() != 0) return java.util.Collections.emptyList();
                    return WindowFrameLookup.parse(bytes.toString("UTF-8"));
                }
                android.os.SystemClock.sleep(5);
            }
        } catch (Exception ignored) { }
        finally { if (process != null) process.destroy(); }
        return java.util.Collections.emptyList();
    }

    private static void addLayoutFrame(JSONObject info, List<WindowFrameLookup.Window> layout,
                                       List<?> compositor) throws Exception {
        WindowFrameLookup.Window window = WindowFrameLookup.find(layout, info.getInt("id"), info.getInt("user"));
        if (window == null) return;
        // When compositor data exists but the app chose a custom title, match the WindowState
        // identity hash from the dump rather than guessing a package or activity.
        if (compositor != null) for (Object row : compositor) {
            Class<?> type = row.getClass();
            String name = (String) type.getField("name").get(row);
            if (name == null || !name.startsWith(window.hash + " ")
                    || !type.getField("isVisible").getBoolean(row)
                    || type.getField("displayId").getInt(row) != 0) continue;
            Rect rect = (Rect) type.getField("bounds").get(row);
            if (rect != null && !rect.isEmpty()) {
                putFrame(info, rect, "compositor-identity");
                return;
            }
        }
        int[] f = window.frame;
        putFrame(info, new Rect(f[0], f[1], f[2], f[3]), "window-manager");
    }

    private static void putFrame(JSONObject info, Rect frame, String source) throws Exception {
        info.put("vl", frame.left).put("vt", frame.top).put("vr", frame.right).put("vb", frame.bottom)
                .put("frameSource", source);
    }

    private static JSONArray layoutDecorations(List<WindowFrameLookup.Window> windows) throws Exception {
        JSONArray result = new JSONArray();
        for (WindowFrameLookup.Window window : windows) {
            if (!window.valid() || !window.name.matches("Smart S freeform (title|corner|restore|bubble) [0-9]+")) continue;
            int[] f = window.frame;
            result.put(new JSONObject().put("name", window.name)
                    .put("l", f[0]).put("t", f[1]).put("r", f[2]).put("b", f[3]));
        }
        return result;
    }

    /** Only our explicitly named controls; used to check real overlay alignment in CI. */
    private static JSONArray decorationFrames(List<?> windows) throws Exception {
        JSONArray result = new JSONArray();
        if (windows == null) return result;
        for (Object window : windows) {
            Class<?> type = window.getClass();
            String name = (String) type.getField("name").get(window);
            if (name == null || !name.matches(".*Smart S freeform (title|corner|restore|bubble) [0-9]+$")
                    || type.getField("displayId").getInt(window) != 0
                    || !type.getField("isVisible").getBoolean(window)) continue;
            Rect frame = (Rect) type.getField("bounds").get(window);
            if (frame != null && !frame.isEmpty()) result.put(new JSONObject().put("name", name)
                    .put("l", frame.left).put("t", frame.top).put("r", frame.right).put("b", frame.bottom));
        }
        return result;
    }

    private static boolean matchesWindow(String name, ComponentName component) {
        if (component == null || name == null) return false;
        for (String candidate : new String[]{component.flattenToString(), component.flattenToShortString()}) {
            if (name.equals(candidate) || name.endsWith(" " + candidate)
                    || name.endsWith(":" + candidate)) return true;
        }
        return false;
    }

    private static void addFrame(JSONObject info, Object task, List<?> tasks, List<?> windows) throws Exception {
        if (windows == null || info.getInt("display") != 0 || !info.getBoolean("visible")) return;
        ComponentName top = (ComponentName) task.getClass().getField("topActivity").get(task);
        if (top == null) return;
        // A matching visible component must belong to only one task. Never attach a caption
        // to a guessed window when two tasks share an indistinguishable activity.
        int matches = 0;
        for (Object candidate : tasks) {
            if (candidate.getClass().getField("isVisible").getBoolean(candidate)
                    && top.equals(candidate.getClass().getField("topActivity").get(candidate))) matches++;
        }
        if (matches != 1) return;
        for (Object window : windows) {
            Class<?> type = window.getClass();
            if (type.getField("displayId").getInt(window) != 0
                    || !type.getField("isVisible").getBoolean(window)
                    || !matchesWindow((String) type.getField("name").get(window), top)) continue;
            Rect frame = (Rect) type.getField("bounds").get(window);
            if (frame != null && !frame.isEmpty()) {
                putFrame(info, frame, "compositor");
                return;
            }
        }
    }

    private static void emit(JSONObject result) {
        System.out.println(PREFIX + result);
        System.out.flush();
    }
}
