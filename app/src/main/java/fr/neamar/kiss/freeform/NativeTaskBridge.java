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
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root required");
            if (args.length < 2) throw new IllegalArgumentException("Missing action or profile");
            Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
            Object vm = runtime.getDeclaredMethod("getRuntime").invoke(null);
            runtime.getDeclaredMethod("setHiddenApiExemptions", String[].class).invoke(vm,
                    (Object) new String[]{"Landroid/os/ServiceManager;", "Landroid/app/IActivityTaskManager",
                            "Landroid/app/TaskInfo;", "Landroid/app/ActivityManager$RunningTaskInfo;",
                            "Landroid/content/res/Configuration;->windowConfiguration",
                            "Landroid/app/WindowConfiguration;", "Landroid/window/"});
            Class<?> atm = Class.forName("android.app.IActivityTaskManager");
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                    .invoke(null, "activity_task");
            Object service = Class.forName("android.app.IActivityTaskManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            int user = Integer.parseInt(args[1]);
            if (user < 0) throw new IllegalArgumentException("Invalid user");
            String action = args[0];
            List<?> tasks = tasks(atm, service);
            if ("list".equals(action)) {
                JSONArray rows = new JSONArray();
                for (Object item : tasks) {
                    JSONObject info = describe(item);
                    if (info != null && info.getInt("user") == user) rows.put(info);
                }
                emit(new JSONObject().put("ok", true).put("tasks", rows));
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
            boolean focus = false;
            switch (action) {
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
            Object organizer = atm.getMethod("getWindowOrganizerController").invoke(service);
            Class.forName("android.window.IWindowOrganizerController")
                    .getMethod("applyTransaction", transactionType).invoke(organizer, transaction);
            if (focus) {
                // The transaction itself succeeded; focus is advisory on ROMs with desktop policy.
                try { atm.getMethod("setFocusedTask", int.class).invoke(service, id); }
                catch (ReflectiveOperationException ignored) { }
            }
            emit(new JSONObject().put("ok", true));
        } catch (Throwable failure) {
            try { emit(new JSONObject().put("ok", false).put("error", failure.toString())); }
            catch (Exception ignored) { System.exit(1); }
        }
    }

    private static List<?> tasks(Class<?> atm, Object service) throws Exception {
        return (List<?>) atm.getMethod("getTasks", int.class, boolean.class, boolean.class, int.class)
                .invoke(service, 200, false, false, 0);
    }

    private static JSONObject describe(Object task) throws Exception {
        Class<?> cls = task.getClass();
        ComponentName component = (ComponentName) cls.getField("baseActivity").get(task);
        if (component == null) return null;
        if (cls.getField("displayId").getInt(task) != 0) return null;
        Configuration configuration = (Configuration) cls.getField("configuration").get(task);
        Object wc = Configuration.class.getField("windowConfiguration").get(configuration);
        Rect bounds = (Rect) wc.getClass().getMethod("getBounds").invoke(wc);
        int mode = (Integer) cls.getMethod("getWindowingMode").invoke(task);
        int type = (Integer) cls.getMethod("getActivityType").invoke(task);
        return new JSONObject().put("id", cls.getField("taskId").getInt(task))
                .put("user", cls.getField("userId").getInt(task))
                .put("component", component.flattenToString()).put("pkg", component.getPackageName())
                .put("mode", mode).put("type", type)
                .put("visible", cls.getField("isVisible").getBoolean(task))
                .put("focused", cls.getField("isFocused").getBoolean(task))
                .put("l", bounds.left).put("t", bounds.top).put("r", bounds.right).put("b", bounds.bottom);
    }

    private static void emit(JSONObject result) {
        System.out.println(PREFIX + result);
        System.out.flush();
        System.exit(0);
    }
}
