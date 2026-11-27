package fr.neamar.kiss.freeform;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Process;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.RootHandler;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** All calls are made on a worker. Every operation passes the selected task's identity to root. */
@RequiresApi(35)
final class NativeTaskClient {
    static final int USER = Process.myUid() / 100000;
    private final Context app;
    NativeTaskClient(Context context) { app = context.getApplicationContext(); }

    static final class Task {
        final int id, user, mode, type;
        final String component, pkg;
        final boolean visible, focused;
        final Rect bounds;
        Task(JSONObject row) throws org.json.JSONException {
            id = row.getInt("id"); user = row.getInt("user"); mode = row.getInt("mode");
            type = row.getInt("type"); component = row.getString("component");
            pkg = row.getString("pkg"); visible = row.getBoolean("visible");
            focused = row.getBoolean("focused");
            bounds = new Rect(row.getInt("l"), row.getInt("t"), row.getInt("r"), row.getInt("b"));
        }
    }

    @Nullable List<Task> list() {
        JSONObject result = invoke("list", "");
        if (result == null || !result.optBoolean("ok")) return null;
        try {
            JSONArray rows = result.getJSONArray("tasks");
            List<Task> tasks = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) tasks.add(new Task(rows.getJSONObject(i)));
            return tasks;
        } catch (org.json.JSONException failure) { return null; }
    }

    boolean action(String action, Task task, @Nullable Rect bounds) {
        if (task == null || task.user != USER || task.type != 1) return false;
        if (!task.component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) return false;
        if (!action.matches("minimize|restore|maximize|resize|focus|close")) return false;
        Rect rect = bounds == null ? new Rect() : bounds;
        JSONObject result = invoke(action, " " + task.id + " " + quote(task.component)
                + " " + rect.left + " " + rect.top + " " + rect.right + " " + rect.bottom);
        return result != null && result.optBoolean("ok");
    }

    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }

    @Nullable private JSONObject invoke(String action, String extra) {
        if (Build.VERSION.SDK_INT < 35) return null;
        RootHandler root = KissApplication.getApplication(app).getRootHandler();
        if (!root.isRootActivated()) return null;
        String command = "/system/bin/env " + quote("CLASSPATH=" + app.getApplicationInfo().sourceDir)
                + " /system/bin/app_process /system/bin " + NativeTaskBridge.class.getName()
                + " " + action + " " + USER + extra;
        String output = root.readPrivilegedOutput(command);
        for (String line : output.split("\\r?\\n")) {
            if (!line.startsWith(NativeTaskBridge.PREFIX)) continue;
            try { return new JSONObject(line.substring(NativeTaskBridge.PREFIX.length())); }
            catch (org.json.JSONException failure) { return null; }
        }
        return null;
    }
}
