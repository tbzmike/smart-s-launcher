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
        final Rect bounds, frame;
        Task(JSONObject row) throws org.json.JSONException {
            id = row.getInt("id"); user = row.getInt("user"); mode = row.getInt("mode");
            type = row.getInt("type"); component = row.getString("component");
            pkg = row.getString("pkg"); visible = row.getBoolean("visible");
            focused = row.getBoolean("focused");
            bounds = new Rect(row.getInt("l"), row.getInt("t"), row.getInt("r"), row.getInt("b"));
            frame = row.has("vl") ? new Rect(row.getInt("vl"), row.getInt("vt"),
                    row.getInt("vr"), row.getInt("vb")) : new Rect();
        }
    }

    @Nullable List<Task> list() {
        return list(true);
    }

    @Nullable List<Task> list(boolean withFrames) {
        JSONObject result = invoke(withFrames ? "list" : "identities", "");
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
        if (!action.matches("minimize|restore|restorePinned|maximize|resize|resizePinned|focus|focusPinned|unpin|back|close")) return false;
        Rect rect = bounds == null ? new Rect() : bounds;
        JSONObject result = invoke(action, " " + task.id + " " + quote(task.component)
                + " " + rect.left + " " + rect.top + " " + rect.right + " " + rect.bottom);
        return result != null && result.optBoolean("ok");
    }

    /** Private transport abstraction; only the debug source set permits instrumentation injection. */
    interface Pipe extends AutoCloseable {
        java.io.OutputStream input();
        java.io.InputStream output();
        boolean alive();
        @Override void close();
    }
    interface Transport {
        String read(String command);
        Pipe open(String command) throws java.io.IOException;
    }
    private Pipe rootPipe(String command) throws java.io.IOException {
        Transport test = FreeformTestTransport.get();
        if (test != null) return test.open(command);
        java.lang.Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        return new Pipe() {
            @Override public java.io.OutputStream input() { return process.getOutputStream(); }
            @Override public java.io.InputStream output() { return process.getInputStream(); }
            @Override public boolean alive() { return process.isAlive(); }
            @Override public void close() { process.destroy(); }
        };
    }

    /** One root process per gesture, with bounded replies and no background idle daemon. */
    final class Gesture implements AutoCloseable {
        private final Pipe process;
        private final java.io.OutputStream input;
        private final java.io.InputStream output;
        private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        Gesture(Task task) throws java.io.IOException {
            if (!task.component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")
                    || task.user != USER || task.type != 1 || task.mode != 5
                    || !KissApplication.getApplication(app).getRootHandler().isRootActivated()) {
                throw new java.io.IOException("Invalid gesture task");
            }
            process = rootPipe(command("gesture", " " + task.id + " " + quote(task.component)));
            input = process.input(); output = process.output();
        }
        @Nullable Task send(Rect bounds, boolean finish) {
            try {
                if (!KissApplication.getApplication(app).getRootHandler().isRootActivated()) return null;
                String message = (finish ? "resize" : "live") + " " + bounds.left + " "
                        + bounds.top + " " + bounds.right + " " + bounds.bottom + "\n";
                input.write(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)); input.flush();
                long deadline = android.os.SystemClock.elapsedRealtime() + 2500;
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    while (output.available() > 0) {
                        int ch = output.read();
                        if (ch < 0) return null;
                        if (ch == '\n') {
                            String response = line.toString("UTF-8").trim(); line.reset();
                            if (response.startsWith(NativeTaskBridge.PREFIX)) {
                                JSONObject value = new JSONObject(response.substring(NativeTaskBridge.PREFIX.length()));
                                JSONObject row = value.optJSONObject("task");
                                return value.optBoolean("ok") && row != null ? new Task(row) : null;
                            }
                        } else if (line.size() < 65536) line.write(ch);
                        else line.reset();
                    }
                    if (!process.alive()) return null;
                    android.os.SystemClock.sleep(8);
                }
            } catch (Exception failure) { return null; }
            return null;
        }
        @Override public void close() {
            try { input.close(); } catch (java.io.IOException ignored) { }
            process.close();
        }
    }

    private String command(String action, String extra) {
        return "/system/bin/env " + quote("CLASSPATH=" + app.getApplicationInfo().sourceDir)
                + " /system/bin/app_process /system/bin " + NativeTaskBridge.class.getName()
                + " " + action + " " + USER + extra;
    }

    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }

    @Nullable private JSONObject invoke(String action, String extra) {
        if (Build.VERSION.SDK_INT < 35) return null;
        RootHandler root = KissApplication.getApplication(app).getRootHandler();
        if (!root.isRootActivated()) return null;
        String command = command(action, extra) + " 2>&1";
        Transport test = FreeformTestTransport.get();
        String output = test == null ? root.readPrivilegedOutput(command) : test.read(command);
        for (String line : output.split("\\r?\\n")) {
            if (!line.startsWith(NativeTaskBridge.PREFIX)) continue;
            try { return new JSONObject(line.substring(NativeTaskBridge.PREFIX.length())); }
            catch (org.json.JSONException failure) { return null; }
        }
        return null;
    }
}
